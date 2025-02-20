package org.opennms.horizon.notifications.api.email;

import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.opennms.horizon.alerts.proto.Alert;
import org.opennms.horizon.notifications.api.LokahiUrlUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Date;
import java.util.Properties;

@Component
public class Velocity {
    private final LokahiUrlUtil lokahiUrlUtil;

    @Value("${spring.mail.template}")
    private String alertTemplate;

    private final VelocityEngine velocityEngine;

    public Velocity(LokahiUrlUtil lokahiUrlUtil) {
        this.lokahiUrlUtil = lokahiUrlUtil;
        Properties props = new Properties();
        props.setProperty("resource.loaders", "class");
        props.setProperty("resource.loader.class.class", "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");

        velocityEngine = new VelocityEngine(props);
        velocityEngine.init();
    }

    public String populateTemplate(String recipient, Alert alert) {
        VelocityContext ctx = new VelocityContext();
        ctx.put("currentYear", LocalDate.now().getYear());
        ctx.put("recipient", recipient);
        ctx.put("alert", alert);
        ctx.put("firstEventTime", new Date(alert.getFirstEventTimeMs()));
        ctx.put("lastUpdateTime", new Date(alert.getLastUpdateTimeMs()));
        ctx.put("url", lokahiUrlUtil.getAlertstUrl(alert));

        StringWriter writer = new StringWriter();
        velocityEngine.mergeTemplate(alertTemplate, StandardCharsets.UTF_8.name(), ctx, writer);

        return writer.toString();
    }
}
