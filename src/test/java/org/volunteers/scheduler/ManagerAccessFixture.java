package org.volunteers.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.volunteers.scheduler.service.PhoneAccess;
import org.volunteers.scheduler.repository.Volunteers;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** Existing business-flow tests run with an authorized manager; PhoneLoginIntegrationTest covers real access checks. */
abstract class ManagerAccessFixture {
    @MockitoBean PhoneAccess phoneAccess;
    @Autowired Volunteers accessVolunteers;
    @Autowired org.volunteers.scheduler.service.SchedulerService accessScheduler;
    @BeforeEach void managerAccess() {
        when(phoneAccess.saveEvent(any(), any(), any())).thenAnswer(call -> accessScheduler.saveEvent(call.getArgument(0), call.getArgument(1)));
        when(phoneAccess.signedIn(any())).thenReturn(true);
        when(phoneAccess.canManage(any(), any())).thenReturn(true);
        when(phoneAccess.manageableEventIds(any())).thenAnswer(call -> accessScheduler.events().stream()
                .map(org.volunteers.scheduler.model.Event::getId).collect(java.util.stream.Collectors.toSet()));
        when(phoneAccess.department(any(), anyLong())).thenReturn(null);
        when(phoneAccess.ids(any())).thenAnswer(call -> accessVolunteers.findAll().stream()
                .map(v -> v.getId()).collect(java.util.stream.Collectors.toSet()));
        when(phoneAccess.matching(any())).thenAnswer(call -> accessVolunteers.findAll());
    }
}
