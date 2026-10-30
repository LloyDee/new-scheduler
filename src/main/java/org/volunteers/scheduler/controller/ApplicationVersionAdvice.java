package org.volunteers.scheduler.controller;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.volunteers.scheduler.version.Version;

@ControllerAdvice
public class ApplicationVersionAdvice {
    private final Version version = new Version();

    @ModelAttribute("appVersion")
    public Version appVersion() {
        return version;
    }
}
