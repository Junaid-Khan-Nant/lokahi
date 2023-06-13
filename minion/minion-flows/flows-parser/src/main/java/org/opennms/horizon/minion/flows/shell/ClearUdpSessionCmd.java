/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2023 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2023 The OpenNMS Group, Inc.
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


package org.opennms.horizon.minion.flows.shell;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.opennms.horizon.minion.flows.listeners.Parser;
import org.opennms.horizon.minion.flows.listeners.UdpParser;
import org.opennms.horizon.minion.flows.parser.FlowsListenerFactory;
import org.opennms.horizon.minion.flows.parser.TelemetryRegistry;

/**
 * Shell command to clear parsers sessions to avoid templates inconsistencies
 */
@Command(scope = "opennms", name = "clear-session", description = "Clear Parsers Sessions to avoid templates inconsistencies")
@Service
public class ClearUdpSessionCmd implements Action {

    @Reference
    FlowsListenerFactory.FlowsListener flowsListener;

    @Option(name = "-f", aliases = "--keyword", description = "specify protocol keyword")
    String keyword = "not-specified";

    @Option(name = "-p", aliases = "--port", description = "specify port")
    int portNumber;

    @Option(name = "-o", aliases = "--observationDomainId", description = "specify observation domain Id")
    int observationDomainId;


    @Override
    public Object execute() throws Exception {
        if (StringUtils.isBlank(keyword)) {
            System.out.println("Please specify a valid feature, e.g. -f netflow9 or --keyword netflow5");
            return null;
        }

        if (portNumber == 0) {
            System.out.println("Please specify a valid port, e.g. -p 1234 or --port 1234");
            return null;
        }

        // Udp Sessions
        // Get Udp Parsers
        List<Parser> udpParsers = new ArrayList<>();
        flowsListener.getListeners()
            .forEach(listener -> udpParsers.addAll(listener.getParsers().stream()
                .filter(parser -> parser instanceof UdpParser)
                .filter(parser -> keyword.equals(parser.getKeyword())).toList()));

                if (udpParsers.isEmpty()) {
                    System.out.printf("The given feature %s could not be matched with any of the following UDP active parsers: %s",
                        keyword, udpParsers);
                    return null;
                }

                udpParsers.forEach(udpParser -> ((UdpParser) udpParser).getSessionManager().getTemplates()
                    .forEach((key, value) -> ((UdpParser) udpParser).getSessionManager().removeTemplateIf((e ->
                        e.getKey().observationDomainId.observationDomainId == this.observationDomainId))));

                System.out.printf("Sessions for protocol UDP and keyword %s successfully dropped.", keyword);
                return null;
            }
    }
