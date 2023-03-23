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

package org.opennms.horizon.alertservice.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.opennms.horizon.alertservice.db.entity.MonitorPolicy;
import org.opennms.horizon.alertservice.db.repository.MonitorPolicyRepository;
import org.opennms.horizon.alertservice.mapper.MonitorPolicyMapper;
import org.opennms.horizon.shared.alert.policy.MonitorPolicyProto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class MonitorPolicyService {
    private final MonitorPolicyMapper policyMapper;
    private final MonitorPolicyRepository repository;

    public MonitorPolicyProto creatPolicy(MonitorPolicyProto request, String tenantId) throws IOException {
        MonitorPolicy policy = policyMapper.protoToEntity(request);
        updateData(policy, tenantId);
        MonitorPolicy newPolicy = repository.save(policy);
        return policyMapper.entityToProto(newPolicy);
    }

    @Transactional
    public List<MonitorPolicyProto> listAll(String tenantId) {
        List<MonitorPolicyProto> result = new ArrayList<>();
        repository.findAllByTenantId(tenantId).forEach(policy -> {
            try {
                result.add(policyMapper.entityToProto(policy));
            } catch (IOException e) {
                log.error("Error while converting policy to proto: {}", policy, e);
            }
        });
        return result;
    }

    @Transactional
    public Optional<MonitorPolicyProto> findById(Long id, String tenantId) throws IOException {
        return repository.findByIdAndTenantId(id, tenantId)
            .map(p -> {
                try {
                    return policyMapper.entityToProto(p);
                } catch (IOException e) {
                    log.error("Error while converting policy to proto: {}", p, e);
                    return null;
                }
            });
    }

    private void updateData(MonitorPolicy policy, String tenantId) {
        policy.setTenantId(tenantId);
        policy.getRules().forEach(r -> {
            r.setTenantId(tenantId);
            r.setPolicy(policy);
            r.getSnmpEvents().forEach(e -> {
                e.setTenantId(tenantId);
                e.setRule(r);
            });
        });
    }
}
