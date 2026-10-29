package org.volunteers.scheduler.service;

import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.volunteers.scheduler.model.DeviceVisit;
import org.volunteers.scheduler.repository.DeviceVisits;

@Service
public class DeviceVisitService {
    private final DeviceVisits visits;
    private final TransactionTemplate transactions;
    public DeviceVisitService(DeviceVisits visits, org.springframework.transaction.PlatformTransactionManager manager) {
        this.visits = visits;
        this.transactions = new TransactionTemplate(manager);
    }
    // Include commit in the monitor to serialize concurrent browser updates in this application.
    public synchronized void record(String id, Map<String, String> data) {
        transactions.executeWithoutResult(status -> {
            DeviceVisit visit = visits.findById(id).orElseGet(() -> {
                DeviceVisit created = new DeviceVisit();
                created.id = id;
                created.firstSeen = Instant.now();
                return created;
            });
            visit.browser = field(data, "browser");
            visit.operatingSystem = field(data, "operatingSystem");
            visit.deviceCategory = field(data, "deviceCategory");
            visit.timezone = field(data, "timezone");
            visit.language = field(data, "language");
            if (data.get("city") != null) visit.city = field(data, "city");
            if (data.get("region") != null) visit.region = field(data, "region");
            if (data.get("country") != null) visit.country = field(data, "country");
            visit.lastSeen = Instant.now();
            visit.visitCount++;
            visits.save(visit);
        });
    }
    private String field(Map<String, String> data, String key) {
        String value = data.get(key);
        return value == null || value.isBlank() ? null : value.strip().substring(0, Math.min(value.strip().length(), 160));
    }
}
