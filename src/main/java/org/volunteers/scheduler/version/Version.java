package org.volunteers.scheduler.version;

public final class Version {
    private final String YEAR = "2027";
    private final String VERSION_NAME = "Scheduler Application";
    private final String VERSION_NUMBER = "3.15";

    public String getYear() { return YEAR; }
    public String getVersionName() { return VERSION_NAME; }
    public String getVersionNumber() { return VERSION_NUMBER; }
    public String getCopyright() { return "\u00a9 " + YEAR + " v." + VERSION_NUMBER; }
}
