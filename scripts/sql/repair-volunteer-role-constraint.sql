-- PostgreSQL only. Run once against the affected application's database.
-- Re-running is safe. Both changes commit together; existing records are preserved.
BEGIN;

ALTER TABLE volunteer_department_roles
    DROP CONSTRAINT IF EXISTS volunteer_department_roles_department_role_check;

ALTER TABLE volunteer_department_roles
    ADD CONSTRAINT volunteer_department_roles_department_role_check
    CHECK (department_role IN (
        'ADMIN', 'OVERSEER', 'ASSISTANT_OVERSEER',
        'CAPTAIN', 'KEY_MAN', 'VOLUNTEER'
    ));

COMMIT;
