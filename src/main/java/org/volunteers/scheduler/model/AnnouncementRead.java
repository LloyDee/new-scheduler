package org.volunteers.scheduler.model;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "scheduler_announcement_read")
@IdClass(AnnouncementRead.Key.class)
public class AnnouncementRead {
    @Id public String deviceId;
    @Id public Long announcementId;

    public static class Key implements Serializable {
        public String deviceId;
        public Long announcementId;
        public Key() {}
        @Override public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(deviceId, key.deviceId)
                    && Objects.equals(announcementId, key.announcementId);
        }
        @Override public int hashCode() { return Objects.hash(deviceId, announcementId); }
    }
}
