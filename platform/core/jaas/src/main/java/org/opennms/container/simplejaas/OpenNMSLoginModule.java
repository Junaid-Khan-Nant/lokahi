/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2012-2014 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2014 The OpenNMS Group, Inc.
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

package org.opennms.container.simplejaas;

import java.security.Principal;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.login.LoginException;

import org.apache.karaf.jaas.boot.principal.RolePrincipal;
import org.apache.karaf.jaas.modules.AbstractKarafLoginModule;
import org.opennms.horizon.core.auth.User;
import org.opennms.horizon.core.auth.UserConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class OpenNMSLoginModule extends AbstractKarafLoginModule implements SimpleOpenNMSLoginHandler {
    private static final transient Logger LOG = LoggerFactory.getLogger(OpenNMSLoginModule.class);
    private Map<String, ?> m_sharedState;

    @Override
    public void initialize(final Subject subject, final CallbackHandler callbackHandler, final Map<String, ?> sharedState, final Map<String, ?> options) {
        LOG.info("~~~~~ OpenNMS Login Module initializing: subject={}, callbackHandler={}, sharedState={}, options={}", subject, callbackHandler, sharedState, options);
        m_sharedState = sharedState;
        super.initialize(subject, callbackHandler, options);
    }

    @Override
    public boolean login() throws LoginException {
        LOG.info("~~~~~ OpenNMS Login Module login in: subject={}, callbackHandler={}, , options={}", subject, callbackHandler, options);
        succeeded = SimpleLoginModuleUtils.doLogin(this, subject, m_sharedState, options);
        return succeeded;
    }

    @Override
    public boolean abort() throws LoginException {
        LOG.info("~~~~~ OpenNMS Login Module aborting");
        return super.abort();
    }

    @Override
    public boolean logout() throws LoginException {
        return super.logout();
    }

    public CallbackHandler callbackHandler() {
        return this.callbackHandler;
    }

    @Override
    public UserConfig userConfig() {
        return JaasSupport.getUserConfig();
    }

    @Override
    public String user() {
        return this.user;
    }

    @Override
    public void setUser(final String user) {
        this.user = user;
    }


/*
    public Set<Principal> createPrincipals(final GrantedAuthority authority) {
        final String role = authority.getAuthority().replaceFirst("^[Rr][Oo][Ll][Ee]_", "");
        final Set<Principal> principals = new HashSet<>();
        principals.add(new RolePrincipal(role));
        principals.add(new RolePrincipal(role.toLowerCase()));
        principals.add(new RolePrincipal(authority.getAuthority()));
        LOG.debug("created principals from authority {}: {}", authority, principals);
        return principals;
    }
 */
    // unused from this class
    private static Set<Principal> createPrincipals(SimpleOpenNMSLoginHandler handler, User configUser) {
        final Set<Principal> principals = new LinkedHashSet<>();
        for (final String role : configUser.getRoles()) {
            principals.add(new RolePrincipal(role));
        }
        return principals;
    }


    @Override
    public Set<Principal> principals() {
        return this.principals;
    }

    @Override
    public void setPrincipals(final Set<Principal> principals) {
        this.principals = principals;
    }

    @Override
    public boolean requiresAdminRole() {
        // this LoginHandler is used for Rest endpoint access in karaf, so admin should not be required
        return false;
    }
}
