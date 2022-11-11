package org.opennms.horizon.alarmservice.rest;

import org.opennms.horizon.alarmservice.drools.AlarmService;
import org.opennms.horizon.alarmservice.model.mapper.AlarmMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackages = "org.opennms.horizon.alarmservice")
public class AlarmRestServiceConfig {


    @Bean("alarmMapper")
    public AlarmMapper alarmMapper() {
        return AlarmMapper.INSTANCE;
    }

    @Bean("alarmRestService")
    public AlarmRestService alarmRestService (
        @Autowired AlarmService alarmService,
        @Autowired AlarmMapper alarmMapper) {
        return new AlarmRestServiceImpl(alarmService, alarmMapper);
    }
}
