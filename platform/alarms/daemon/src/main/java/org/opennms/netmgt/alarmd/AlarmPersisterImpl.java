/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2009-2018 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2018 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.netmgt.alarmd;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.stream.Collectors;

import org.opennms.horizon.alarms.api.AlarmEntityNotifier;
import org.opennms.horizon.alarms.api.AlarmPersisterExtension;
import org.opennms.horizon.core.lib.SystemProperties;
import org.opennms.horizon.db.dao.api.AlarmDao;
import org.opennms.horizon.db.dao.api.EventDao;
import org.opennms.horizon.db.dao.api.SessionUtils;
import org.opennms.horizon.db.model.OnmsAlarm;
import org.opennms.horizon.db.model.OnmsEvent;
import org.opennms.horizon.db.model.OnmsSeverity;
import org.opennms.horizon.events.conf.xml.LogDestType;
import org.opennms.horizon.events.xml.Event;
import org.opennms.horizon.events.xml.Parm;
import org.opennms.horizon.events.xml.UpdateField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Striped;

/**
 * Singleton to persist OnmsAlarms.
 *
 * @author <a href="mailto:david@opennms.org">David Hustace</a>
 * @version $Id: $
 */
public class AlarmPersisterImpl implements AlarmPersister {
    private static final Logger LOG = LoggerFactory.getLogger(AlarmPersisterImpl.class);

    public static final String RELATED_REDUCTION_KEY_PREFIX = "related-reductionKey";

    protected static final Integer NUM_STRIPE_LOCKS = SystemProperties.getInteger("org.opennms.alarmd.stripe.locks", Alarmd.THREADS * 4);
    protected static boolean NEW_IF_CLEARED = Boolean.getBoolean("org.opennms.alarmd.newIfClearedAlarmExists");
    protected static boolean LEGACY_ALARM_STATE = Boolean.getBoolean("org.opennms.alarmd.legacyAlarmState");

    private AlarmDao m_alarmDao;

    private EventDao m_eventDao;

    private SessionUtils sessionUtils;

    private AlarmEntityNotifier m_alarmEntityNotifier;

    private Striped<Lock> lockStripes = StripedExt.fairLock(NUM_STRIPE_LOCKS);

    private final Set<AlarmPersisterExtension> extensions = Sets.newConcurrentHashSet();

    private boolean m_createNewAlarmIfClearedAlarmExists = LEGACY_ALARM_STATE == true ? false : NEW_IF_CLEARED;
    
    private boolean m_legacyAlarmState = LEGACY_ALARM_STATE;

    @Override
    public OnmsAlarm persist(Event event) {
        Objects.requireNonNull(event, "Cannot create alarm from null event.");
        if (!checkEventSanityAndDoWeProcess(event)) {
            return null;
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("process: {}; nodeid: {}; ipaddr: {}; serviceid: {}", event.getUei(), event.getNodeid(), event.getInterface(), event.getService());
        }

        // Lock both the reduction and clear keys (if set) using a fair striped lock
        // We do this to ensure that clears and triggers are processed in the same order
        // as the calls are made
        final Iterable<Lock> locks = lockStripes.bulkGet(getLockKeys(event));
        final OnmsAlarm[] alarm = new OnmsAlarm[1];
        try {
            locks.forEach(Lock::lock);
            // Process the alarm inside a transaction
            sessionUtils.withTransaction(()->{
                alarm[0] = addOrReduceEventAsAlarm(event);
            });
        } finally {
            locks.forEach(Lock::unlock);
        }

        return alarm[0];
    }

    private OnmsAlarm addOrReduceEventAsAlarm(Event event) throws IllegalStateException {
        
        final OnmsEvent persistedEvent = m_eventDao.get(event.getDbid());
        if (persistedEvent == null) {
            throw new IllegalStateException("Event with id " + event.getDbid() + " was deleted before we could retrieve it and create an alarm.");
        }

        final String reductionKey = event.getAlarmData().getReductionKey();
        LOG.debug("addOrReduceEventAsAlarm: looking for existing reduction key: {}", reductionKey);
        
        String key = reductionKey;
        String clearKey = event.getAlarmData().getClearKey();
        
        boolean didSwapReductionKeyWithClearKey = false;
        if (!m_legacyAlarmState && clearKey != null && isResolutionEvent(event)) {
            key = clearKey;
            didSwapReductionKeyWithClearKey = true;
        }

        OnmsAlarm alarm = m_alarmDao.findByReductionKey(key);

        if (alarm == null && didSwapReductionKeyWithClearKey) {
            // if the clearKey returns null, still need to check the reductionKey
            alarm = m_alarmDao.findByReductionKey(reductionKey);
        }

        if (alarm == null || (m_createNewAlarmIfClearedAlarmExists && OnmsSeverity.CLEARED.equals(alarm.getSeverity()))) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("addOrReduceEventAsAlarm: reductionKey:{} not found, instantiating new alarm", reductionKey);
            }

            if (alarm != null) {
                LOG.debug("addOrReduceEventAsAlarm: \"archiving\" cleared Alarm for problem: {}; " +
                        "A new alarm will be instantiated to manage the problem.", reductionKey);
                alarm.archive();
                m_alarmDao.save(alarm);
                m_alarmDao.flush();

                m_alarmEntityNotifier.didArchiveAlarm(alarm, reductionKey);
            }

            alarm = createNewAlarm(persistedEvent, event);

            // Trigger extensions, allowing them to mangle the alarm
            try {
                final OnmsAlarm alarmCreated = alarm;
                extensions.forEach(ext -> ext.afterAlarmCreated(alarmCreated, event, persistedEvent));
            } catch (Exception ex) {
                LOG.error("An error occurred while invoking the extension callbacks.", ex);
            }

            m_alarmDao.save(alarm);
            m_eventDao.saveOrUpdate(persistedEvent);

            m_alarmEntityNotifier.didCreateAlarm(alarm);
        } else {
            LOG.debug("addOrReduceEventAsAlarm: reductionKey:{} found, reducing event to existing alarm: {}", reductionKey, alarm.getId());
            reduceEvent(persistedEvent, alarm, event);

            // Trigger extensions, allowing them to mangle the alarm
            try {
                final OnmsAlarm alarmUpdated = alarm;
                extensions.forEach(ext -> ext.afterAlarmUpdated(alarmUpdated, event, persistedEvent));
            } catch (Exception ex) {
                LOG.error("An error occurred while invoking the extension callbacks.", ex);
            }

            m_alarmDao.update(alarm);
            m_eventDao.update(persistedEvent);

            if (event.getAlarmData().isAutoClean()) {
                m_eventDao.deletePreviousEventsForAlarm(alarm.getId(), persistedEvent);
            }

            m_alarmEntityNotifier.didUpdateAlarmWithReducedEvent(alarm);
        }
        return alarm;
    }

    private void reduceEvent(OnmsEvent persistedEvent, OnmsAlarm alarm, Event event) {
        // Always set these
        alarm.setLastEvent(persistedEvent);
        alarm.setLastEventTime(persistedEvent.getEventTime());
        
        if (!isResolutionEvent(event)) {
            incrementCounter(alarm);
            
            if (isResolvedAlarm(alarm)) {
                resetAlarmSeverity(persistedEvent, alarm);
            }
        } else {
            
            if (isResolvedAlarm(alarm)) {
                incrementCounter(alarm);
            } else {
                alarm.setSeverity(OnmsSeverity.CLEARED);
            }
        }
        alarm.setAlarmType(event.getAlarmData().getAlarmType());

        if (!event.getAlarmData().hasUpdateFields()) {

            //We always set these even if there are not update fields specified
            alarm.setLogMsg(persistedEvent.getEventLogMsg());
        } else {
            for (UpdateField field : event.getAlarmData().getUpdateFieldList()) {
                String fieldName = field.getFieldName();

                //Always set these, unless specified not to, in order to maintain current behavior
                if (fieldName.equalsIgnoreCase("LogMsg") && !field.isUpdateOnReduction()) {
                    continue;
                } else {
                    alarm.setLogMsg(persistedEvent.getEventLogMsg());
                }

                //Set these others
                if (field.isUpdateOnReduction()) {

                    if (fieldName.toLowerCase().startsWith("distpoller")) {
                        alarm.setDistPoller(persistedEvent.getDistPoller());
                    } else if (fieldName.toLowerCase().startsWith("ipaddr")) {
                        alarm.setIpAddr(persistedEvent.getIpAddr());
                    } else if (fieldName.toLowerCase().startsWith("mouseover")) {
                        alarm.setMouseOverText(persistedEvent.getEventMouseOverText());
                    } else if (fieldName.toLowerCase().startsWith("operinstruct")) {
                        alarm.setOperInstruct(persistedEvent.getEventOperInstruct());
                    } else if (fieldName.equalsIgnoreCase("severity")) {
                        resetAlarmSeverity(persistedEvent, alarm);
                    } else if (fieldName.toLowerCase().contains("descr")) {
                        alarm.setDescription(persistedEvent.getEventDescr());
                    } else {
                        LOG.warn("reduceEvent: The specified field: {}, is not supported.", fieldName);
                    }
                }
            }
        }

        updateRelatedAlarms(alarm, event);

        persistedEvent.setAlarm(alarm);
    }
    
    private void updateRelatedAlarms(OnmsAlarm alarm, Event event) {
        // Retrieve the related alarms as given by the event parameters
        final Set<OnmsAlarm> relatedAlarms = getRelatedAlarms(event.getParmCollection());
        // Index these by id
        final Map<Integer, OnmsAlarm> relatedAlarmsByIds = relatedAlarms.stream()
                .collect(Collectors.toMap(OnmsAlarm::getId, a -> a));

        // Build sets of the related alarm ids for easy comparison
        final Set<Integer> relatedAlarmIdsFromEvent = ImmutableSet.copyOf(relatedAlarmsByIds.keySet());
        final Set<Integer> relatedAlarmIdsFromExistingAlarm = ImmutableSet.copyOf(alarm.getRelatedAlarmIds());

        // Remove alarms that are not referenced in the event -  we treat the event as an
        // authoritative source of the related alarms rather than using the union of the previously known related alarms
        // and the event's related alarms
        Sets.difference(relatedAlarmIdsFromExistingAlarm, relatedAlarmIdsFromEvent)
                .forEach(alarm::removeRelatedAlarmWithId);
        // Add new alarms that are referenced in the event, but are not already associated
        Sets.difference(relatedAlarmIdsFromEvent, relatedAlarmIdsFromExistingAlarm)
                .forEach(relatedAlarmIdToAdd -> {
                    final OnmsAlarm related = relatedAlarmsByIds.get(relatedAlarmIdToAdd);
                    if (related != null) {
                        if (!formingCyclicGraph(alarm, related)) {
                            alarm.addRelatedAlarm(related);
                        } else {
                            LOG.warn("Alarm with id '{}' , reductionKey '{}' is not added as related alarm for id '{}' as it is forming cyclic graph ",
                                    related.getId(), related.getReductionKey(), alarm.getId());
                        }
                    }
                });
    }

    private void resetAlarmSeverity(OnmsEvent persistedEvent, OnmsAlarm alarm) {
        alarm.setSeverity(OnmsSeverity.valueOf(persistedEvent.getSeverityLabel()));
    }

    private void incrementCounter(OnmsAlarm alarm) {
        alarm.setCounter(alarm.getCounter() + 1);
    }

    private boolean isResolvedAlarm(OnmsAlarm alarm) {
        return alarm.getAlarmType() == OnmsAlarm.RESOLUTION_TYPE;
    }

    private boolean isResolutionEvent(Event event) {
        return Objects.equals(event.getAlarmData().getAlarmType(), Integer.valueOf(OnmsAlarm.RESOLUTION_TYPE));
    }

    private OnmsAlarm createNewAlarm(OnmsEvent e, Event event) {
        OnmsAlarm alarm = new OnmsAlarm();
        // Situations are denoted by the existance of related-reductionKeys
        alarm.setRelatedAlarms(getRelatedAlarms(event.getParmCollection()), event.getTime());
        alarm.setAlarmType(event.getAlarmData().getAlarmType());
        alarm.setClearKey(event.getAlarmData().getClearKey());
        alarm.setCounter(1);
        alarm.setDescription(e.getEventDescr());
        alarm.setDistPoller(e.getDistPoller());
        alarm.setFirstEventTime(e.getEventTime());
        alarm.setIfIndex(e.getIfIndex());
        alarm.setIpAddr(e.getIpAddr());
        alarm.setLastEventTime(e.getEventTime());
        alarm.setLastEvent(e);
        alarm.setLogMsg(e.getEventLogMsg());
        alarm.setMouseOverText(e.getEventMouseOverText());
        alarm.setNode(e.getNode());
        alarm.setOperInstruct(e.getEventOperInstruct());
        alarm.setReductionKey(event.getAlarmData().getReductionKey());
        alarm.setServiceType(e.getServiceType());
        alarm.setSeverity(OnmsSeverity.get(e.getEventSeverity()));
        alarm.setSuppressedUntil(e.getEventTime()); //UI requires this be set
        alarm.setSuppressedTime(e.getEventTime()); // UI requires this be set
        alarm.setUei(e.getEventUei());
        if (event.getAlarmData().getManagedObject() != null) {
            alarm.setManagedObjectType(event.getAlarmData().getManagedObject().getType());
        }
        e.setAlarm(alarm);
        return alarm;
    }

    private boolean formingCyclicGraph(OnmsAlarm situation, OnmsAlarm relatedAlarm) {

        return situation.getReductionKey().equals(relatedAlarm.getReductionKey()) ||
                relatedAlarm.getRelatedAlarms().stream().anyMatch(ra -> formingCyclicGraph(situation, ra));
    }
    
    private Set<OnmsAlarm> getRelatedAlarms(List<Parm> list) {
        if (list == null || list.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> reductionKeys = list.stream().filter(AlarmPersisterImpl::isRelatedReductionKeyWithContent).map(p -> p.getValue().getContent()).collect(Collectors.toSet());
        // Only existing alarms are returned. Reduction Keys for non-existing alarms are dropped.
        return reductionKeys.stream().map(reductionKey -> m_alarmDao.findByReductionKey(reductionKey)).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private static boolean isRelatedReductionKeyWithContent(Parm param) {
        return param.getParmName() != null
                // TOOD revisit using equals() when event_parameters table supports multiple params with the same name (see NMS-10214)
                && param.getParmName().startsWith(RELATED_REDUCTION_KEY_PREFIX)
                && param.getValue() != null
                && param.getValue().getContent() != null;
    }

    private static boolean checkEventSanityAndDoWeProcess(final Event event) {
        if (event.getLogmsg() != null && LogDestType.DONOTPERSIST.toString().equalsIgnoreCase(event.getLogmsg().getDest())) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("checkEventSanity: uei '{}' marked as '{}'; not processing event.", event.getUei(), LogDestType.DONOTPERSIST);
            }
            return false;
        }

        if (event.getAlarmData() == null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("checkEventSanity: uei '{}' has no alarm data; not processing event.", event.getUei());
            }
            return false;
        }

        if (event.getDbid() <= 0) {
            throw new IllegalArgumentException("Incoming event has an illegal dbid (" + event.getDbid() + "), aborting");
        }

        return true;
    }

    private static Collection<String> getLockKeys(Event event) {
        if (event.getAlarmData().getClearKey() == null) {
            return Collections.singletonList(event.getAlarmData().getReductionKey());
        } else {
            return Arrays.asList(event.getAlarmData().getReductionKey(), event.getAlarmData().getClearKey());
        }
    }


    /**
     * <p>setAlarmDao</p>
     *
     * @param alarmDao a {@link AlarmDao} object.
     */
    public void setAlarmDao(AlarmDao alarmDao) {
        m_alarmDao = alarmDao;
    }

    /**
     * <p>getAlarmDao</p>
     *
     * @return a {@link AlarmDao} object.
     */
    public AlarmDao getAlarmDao() {
        return m_alarmDao;
    }

    /**
     * <p>setEventDao</p>
     *
     * @param eventDao a {@link EventDao} object.
     */
    public void setEventDao(EventDao eventDao) {
        m_eventDao = eventDao;
    }

    /**
     * <p>getEventDao</p>
     *
     * @return a {@link EventDao} object.
     */
    public EventDao getEventDao() {
        return m_eventDao;
    }

    public AlarmEntityNotifier getAlarmChangeListener() {
        return m_alarmEntityNotifier;
    }

    public void setAlarmChangeListener(AlarmEntityNotifier alarmEntityNotifier) {
        m_alarmEntityNotifier = alarmEntityNotifier;
    }

    public void onExtensionRegistered(final AlarmPersisterExtension ext, final Map<String,String> properties) {
        LOG.debug("onExtensionRegistered: {} with properties: {}", ext, properties);
        if (ext==null) { return; }
        extensions.add(ext);
    }

    public void onExtensionUnregistered(final AlarmPersisterExtension ext, final Map<String,String> properties) {
        LOG.debug("onExtensionUnregistered: {} with properties: {}", ext, properties);
        if (ext==null) { return; }
        extensions.remove(ext);
    }

    public boolean isCreateNewAlarmIfClearedAlarmExists() {
        return m_createNewAlarmIfClearedAlarmExists;
    }

    public void setCreateNewAlarmIfClearedAlarmExists(boolean createNewAlarmIfClearedAlarmExists) {
        m_createNewAlarmIfClearedAlarmExists = createNewAlarmIfClearedAlarmExists;
    }
    public boolean isLegacyAlarmState() {
        return m_legacyAlarmState;
    }

    public void setLegacyAlarmState(boolean legacyAlarmState) {
        m_legacyAlarmState = legacyAlarmState;
    }

    public void setAlarmEntityNotifier(AlarmEntityNotifier m_alarmEntityNotifier) {
        this.m_alarmEntityNotifier = m_alarmEntityNotifier;
    }

    public void setSessionUtils(SessionUtils sessionUtils) {
        this.sessionUtils = sessionUtils;
    }
}
