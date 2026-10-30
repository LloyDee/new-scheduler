package org.volunteers.scheduler;

import java.util.List;
import org.springframework.mock.web.MockHttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.volunteers.scheduler.repository.*;
import org.volunteers.scheduler.service.*;
import static org.mockito.Mockito.*;

class PhoneLookupTest {
    @AfterEach void cleanup() { RequestContextHolder.resetRequestAttributes(); }

    @Test void getReusesLookupOnlyWithinTheSameRequest() {
        Volunteers volunteers = mock(Volunteers.class);
        when(volunteers.findByNormalizedPhone("2105550100")).thenReturn(List.of());
        PhoneAccess access = new PhoneAccess(volunteers, mock(Events.class), mock(SchedulerService.class));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("GET", "/events")));
        access.matching("+1 (210) 555-0100");
        access.matching("2105550100");
        verify(volunteers).findByNormalizedPhone("2105550100");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("GET", "/events")));
        access.matching("2105550100");
        verify(volunteers, times(2)).findByNormalizedPhone("2105550100");
        verify(volunteers, never()).findAll();
    }

    @Test void writesAlwaysReadFreshRecords() {
        Volunteers volunteers = mock(Volunteers.class);
        PhoneAccess access = new PhoneAccess(volunteers, mock(Events.class), mock(SchedulerService.class));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("POST", "/login")));
        access.matching("2105550100");
        access.matching("2105550100");
        verify(volunteers, times(2)).findByNormalizedPhone("2105550100");
    }

    @Test void permissionChecksShareOneScalarQueryOnGetAndPost() {
        for (String method : List.of("GET", "POST")) {
            Volunteers volunteers = mock(Volunteers.class);
            when(volunteers.findAccessByPhone("2105550100")).thenReturn(List.of());
            PhoneAccess access = new PhoneAccess(volunteers, mock(Events.class), mock(SchedulerService.class));
            var session = new MockHttpSession();
            session.setAttribute(PhoneAccess.SESSION, "2105550100");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(method, "/events")));
            access.signedIn(session);
            access.canManage(session, null);
            access.ids(session);
            access.department(session, 1L);
            access.manageableEventIds(session);
            verify(volunteers).findAccessByPhone("2105550100");
            verify(volunteers, never()).findByNormalizedPhone(any());
        }
    }
}
