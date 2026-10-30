# Gather · Volunteer Scheduler

A mobile-friendly, server-rendered scheduler for Regional Conventions and Circuit Assemblies. Built with Java 21, Spring Boot, Gradle (Groovy DSL), Thymeleaf, Spring Data JPA, and H2.

## Run

Install JDK 21 and set `JAVA_HOME` to its installation directory. The checked-in Gradle Wrapper downloads the pinned Gradle distribution automatically; no global Gradle installation is required.

```powershell
.\gradlew.bat bootRun
```

On macOS/Linux, use `./gradlew bootRun` (or `sh gradlew bootRun`). Open **http://localhost:8080**. Create an event, add departments and volunteers, create shifts, then assign volunteers.

```powershell
.\gradlew.bat test
.\gradlew.bat bootJar
java -jar build/libs/volunteer-scheduler-0.1.0.jar
```

Tests use an isolated in-memory database. To add test data to H2, run `.\gradlew.bat bootRun --args="--scheduler.seed-test-data=true"`. This creates a separate Volunteer Test Event with 100 volunteers (10 in multiple departments), five departments, and one Overseer plus two Assistant Overseers per department. Repeating the command skips the existing sample event.

The H2 console is available at **http://localhost:8080/h2-console/**. Connect with JDBC URL `jdbc:h2:file:./data/scheduler`, username `sa`, and an empty password. Restart the app after changing console configuration. Set `spring.h2.console.enabled=false` to disable it.

## Development in IntelliJ

Spring Boot DevTools is included for development and excluded from the executable JAR. It disables Thymeleaf and static-resource caching during development and automatically restarts the application when compiled Java classes change.

One-time setup:

1. Reload the Gradle project after pulling these changes.
2. In **Settings > Build, Execution, Deployment > Compiler**, confirm **Build project automatically** is enabled. The project configuration enables this option.
3. In **Settings > Advanced Settings > Compiler**, enable **Allow auto-make to start even if developed application is currently running**. This is an IntelliJ application setting and must be enabled locally.
4. Start (or restart once) `SchedulerApplication` using IntelliJ Run so DevTools is loaded. Open **http://localhost:8080**.

After editing, save your changes and wait for IntelliJ's automatic build to finish. Refresh the existing browser tab to see HTML templates, CSS, and JavaScript updates. Java changes cause a short automatic application restart; wait for the application to finish starting before refreshing. You do not need to reopen the browser tab or install a LiveReload extension.

Automatic reload requires successful compilation. If an edit does not appear, check the Build output for errors; **Build > Build Project** also triggers DevTools. Gradle dependency or build-script changes may require reloading Gradle and manually restarting the app. Existing records remain in the file-backed database across automatic restarts.

The terminal `bootRun` command above does not automatically compile source changes. This refresh workflow assumes IntelliJ Run with automatic builds enabled.

## Features

- Bottom navigation: Home, Volunteers, Schedule, Departments, and More.
- Independent data for every event; event switching under More → Events.
- Volunteer contacts, congregation, notes, search, and personal assignments.
- Choose **No Department Yet** when adding a volunteer to skip department roles. These volunteers are available across departments until their first successful shift assignment saves that department with the **Volunteer** role. Editing a volunteer requires a department and role.
- Department shifts with event-date validation, required headcount, and coverage status.
- Explicit confirmation for overlapping assignments, including conflicts caused by shift edits. Adjacent shifts are allowed. Duplicate assignments are rejected. Overstaffing is allowed.
- Date and department filters, printable reports, and CSV downloads for department schedules, volunteer assignments, and staffing coverage.
- Browser-persisted 12/24-hour display preference. Reports print the selected filters; CSV times use 24-hour format for portability.
- CSRF protection on writes and confirmation dialogs for destructive actions.

## Data and operation

### PostgreSQL volunteer role constraint repair

If saving or importing a Key Man fails with constraint
`volunteer_department_roles_department_role_check`, run
[`scripts/sql/repair-volunteer-role-constraint.sql`](scripts/sql/repair-volunteer-role-constraint.sql)
once in the affected PostgreSQL database (for example, in Supabase's SQL Editor).
Run the complete script, including `BEGIN` and `COMMIT`, using the application's
schema/search path. It replaces only that check constraint and preserves existing
volunteers and department assignments. The script is safe to run again and allows
every current role, including `KEY_MAN` and `CAPTAIN`, while rejecting unknown roles.
Hibernate's automatic schema update is not a replacement for this repair on an
existing database. The script is manual; restarting the app does not run it.

After the script succeeds, restart the app with the updated import error handling
and retry the CSV in Settings > Data. No CSV edits are needed. An import rejected
by the database rolls back in full and now displays an error in the Data card;
the server log retains the underlying exception for diagnosis.

### Event CSV backups

Open the event, then go to **More → Reports** and choose one of the three **All … information (backup)** options. Download the volunteer, schedule/shift, and department CSV files together. The three files share a backup set identifier in their filenames and include department roles and volunteer assignments. Use **Capture new backup** to include subsequent changes, then download all three files again.

To restore, open the destination event and expand **Restore event from CSV backup** on Reports. Upload all three original CSV files from the same backup set (up to 10 MB each) and confirm replacement. Restoration replaces that event's departments, volunteers, shifts, and assignments; it keeps the event details and other events unchanged. Shift dates must fit the destination event. Validation failures leave existing data intact. Ordinary report CSVs are not backup files; keep backup CSVs unedited for restoration.

The default database is **`./data/scheduler.mv.db`**, relative to the application's working directory. Use the same working directory when restarting to retain data. Stop the app before copying the `data` directory for backup. Event deletion permanently removes all its volunteers, departments, shifts, and assignments. Deleting a department removes its shifts; deleting a volunteer removes their assignments.

The first version has no login and binds to `127.0.0.1`. For testing on a phone on your trusted local network, start with `--args='--server.address=0.0.0.0'` and visit your computer's LAN IP on port 8080. Everyone with network access to this instance can manage its data. Authentication is needed before public hosting.

Event dates and times are local wall-clock values; overnight shifts and timezone conversion are not supported. The active event is remembered for the browser session. Volunteer name, phone, email, congregation, privilege, and at least one department with a role are required; notes are optional. Each department allows at most one Overseer and two Assistant Overseers. Each shift requires 1–10,000 volunteers.

Schema creation/update is automatic through Hibernate for this initial H2 version. Introduce versioned migrations before production use or changing database engines.

## Manual acceptance checks

### Device Logs

Open **More → Logs** with management access. Logs open without a password; users without management access receive HTTP 403. The separate notification-send password still uses `SCHEDULER_LOGS_PASSWORD` or the `scheduler.logs.password` Spring property.

Each browser gets a random, HttpOnly identifier cookie lasting one year. Page loads update its anonymous summary in `scheduler_device_visit`; clearing cookies creates another summary. Names are reserved for future account integration. Summaries persist across restarts and are not automatically deleted. Concurrent updates are serialized within one application instance; multiple replicas would require database-level concurrency coordination.

The browser queries ipapi.co for approximate city, state, and country, caching successful results for 24 hours. Failed or blocked lookups do not prevent recording. Browsers blocking JavaScript may not be recorded. No historical visits are backfilled.

Run backend tests with `./gradlew test` and the mocked browser tracking checks with `node --test src/test/js/device-visits.test.cjs`.

1. At a 360px viewport, navigate all five tabs, use each form, and verify the bottom navigation does not obscure content. Check keyboard focus and the skip link.
2. Create one event of each type and verify their volunteers and departments stay separate.
3. Assign a volunteer to overlapping shifts: cancel once, then explicitly confirm. Edit an assigned shift to introduce a conflict and confirm its warning. Test adjacent shifts too.
4. Print each report and inspect date/department filters and page breaks. Download CSV with names containing commas, quotes, and non-ASCII characters.
5. Stop and restart from the same directory; verify events and assignments remain. Delete a populated event and verify the other event remains.

### Phone-only sign-in

All application pages now require a volunteer phone number. `/login` matches normalized
US numbers globally across event volunteer records (10 digits or `+1`). No SMS, password,
or ownership verification is performed. Anyone who knows a registered number can use
that identity, including its management permissions; this is an intentional trust-based mode.

`/signup` reuses the volunteer editor, asks which event to join, rejects an existing phone
number globally, and creates a profile without department roles. An organizer assigns
roles afterward. Existing volunteer records with the same normalized number across events
are treated as one person. Review shared/duplicate numbers before deployment. Management
imports and backup restores retain event-specific records; global duplicate rejection applies
to self-registration. A blank database must be provisioned with an event and a management
volunteer before use (there is deliberately no public administrator-registration route).

Admin, Overseer, Assistant Overseer and Captain can manage the selected event. Being a
manager in one event does not grant management access to another. These roles can also
create events; the creator is added as Admin in an Administration department of the new event. Other roles can only edit their own contact profile; department roles cannot be changed by self-edit. Contact edits are synchronized across their event
records. Notification sending still requires the existing notification unlock in addition
to an authorized role.

Home lists the signed-in person's upcoming shifts. Schedule and Volunteers initially use
their lowest-ID department in the current event when they belong to several; choosing
All departments overrides that default. Names are highlighted by volunteer identity.
Sessions retain the phone identity until expiration or logout; roles are read on each request.

Run `bash gradlew test` with Java 21 and `node --test src/test/js/*.test.cjs`.
`PhoneLoginIntegrationTest` exercises real login and permission checks. Existing business-flow
integration tests use `ManagerAccessFixture` to keep their scheduling assertions independent
of login setup. GitHub Actions runs both suites for pushes and pull requests.

### App Owner and event administrators

`scheduler.app-owner-phone` grants account-wide access independently of department assignments. It defaults to `8135160335`; set `SCHEDULER_APP_OWNER_PHONE` to override it. The app continues to use its existing phone-based login.

App Owners manage all events. Event administrators manage their own event, appoint other administrators, and delete the event. Other organizers retain their existing management access within authorized events, without administrator-management or event-deletion access. Logs and notification management are available only to App Owners, event administrators, Overseers, and Assistant Overseers. Non-owner access follows the selected event; before selection, a qualifying role in any event is sufficient. Notification receiving remains available to every role. Use **Event administrator** in the volunteer editor to transfer responsibility: appoint a replacement before demoting or deleting the last administrator.

On startup, existing department Admin roles migrate to event administrator permissions, retaining department membership as Volunteer. Events without administrators receive an administrator membership for the configured App Owner. This migration is repeat-safe. Newly created events assign their creator as administrator; service-created/sample events use the configured owner.

Backups now use version 2 and retain event administrator status. Version 1 backups remain supported, converting legacy department Admin roles. Restores require an App Owner or event administrator and are rejected if they would leave the event without an administrator.

### Access query regression checks

`AccessQueryIntegrationTest` measures real HTTP request boundaries with Hibernate statistics and captured SQL. It covers App Owner, event Admin, Overseer, Assistant Overseer, Captain, Key Man, and Volunteer, including partial navigation and more than one page of events. Access lookups are cached only within each request; event permission controls use the already-loaded Events page. Managed volunteer writes acquire one event lock and validate administrator changes within that transaction.

Measured with the local H2 fixture: App Owner Schedule requests decreased from 8 to 6 SQL statements; Settings and Logs decreased from 4 to 2. These are database statement counts, not production response-time guarantees. Run `gradlew.bat test` to check query budgets together with access and administrator-safety tests.
