ALTER TABLE yumpoo.identity_user
    ADD COLUMN workspace_slug varchar(64) NULL;

CREATE OR REPLACE FUNCTION yumpoo.normalize_workspace_slug(source text)
RETURNS varchar
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS $$
DECLARE
    candidate text;
BEGIN
    candidate := lower(source);
    candidate := regexp_replace(candidate, '[^a-z0-9._@-]+', '-', 'g');
    candidate := regexp_replace(candidate, '^[^a-z0-9]+', '', 'g');
    candidate := regexp_replace(candidate, '[^a-z0-9]+$', '', 'g');
    candidate := left(candidate, 64);
    candidate := regexp_replace(candidate, '[^a-z0-9]+$', '', 'g');

    IF candidate = '' OR candidate IN ('me', 'new', 'admin', 'settings') THEN
        RETURN NULL;
    END IF;
    RETURN candidate::varchar;
END;
$$;

UPDATE yumpoo.identity_user
SET workspace_slug = 'u-' || replace(id::text, '-', '');

DO $$
DECLARE
    member record;
    preferred varchar(64);
    alternate varchar(64);
    suffix text;
    stem text;
BEGIN
    FOR member IN
        SELECT identity_user.id,
               identity_user.company_id,
               external_identity.external_user_id
        FROM yumpoo.identity_user identity_user
        LEFT JOIN yumpoo.external_identity external_identity
          ON external_identity.user_id = identity_user.id
         AND external_identity.company_id = identity_user.company_id
         AND external_identity.provider = 'WECOM'
        ORDER BY identity_user.company_id, identity_user.created_at, identity_user.id
    LOOP
        preferred := yumpoo.normalize_workspace_slug(member.external_user_id);
        IF preferred IS NULL THEN
            CONTINUE;
        END IF;

        IF NOT EXISTS (
            SELECT 1
            FROM yumpoo.identity_user existing
            WHERE existing.company_id = member.company_id
              AND existing.id <> member.id
              AND existing.workspace_slug = preferred
        ) THEN
            UPDATE yumpoo.identity_user
            SET workspace_slug = preferred
            WHERE id = member.id;
            CONTINUE;
        END IF;

        suffix := '-' || left(replace(member.id::text, '-', ''), 8);
        stem := left(preferred, 64 - length(suffix));
        stem := regexp_replace(stem, '[^a-z0-9]+$', '', 'g');
        alternate := CASE WHEN stem = '' THEN NULL ELSE stem || suffix END;
        IF alternate IS NOT NULL AND NOT EXISTS (
            SELECT 1
            FROM yumpoo.identity_user existing
            WHERE existing.company_id = member.company_id
              AND existing.id <> member.id
              AND existing.workspace_slug = alternate
        ) THEN
            UPDATE yumpoo.identity_user
            SET workspace_slug = alternate
            WHERE id = member.id;
        END IF;
    END LOOP;
END;
$$;

ALTER TABLE yumpoo.identity_user
    ALTER COLUMN workspace_slug SET NOT NULL,
    ADD CONSTRAINT uq_identity_user_company_workspace_slug
        UNIQUE (company_id, workspace_slug),
    ADD CONSTRAINT ck_identity_user_workspace_slug CHECK (
        workspace_slug ~ '^[a-z0-9]([a-z0-9._@-]{0,62}[a-z0-9])?$'
        AND workspace_slug NOT IN ('me', 'new', 'admin', 'settings')
    );

CREATE OR REPLACE FUNCTION yumpoo.protect_identity_workspace_slug()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.workspace_slug IS NULL THEN
            NEW.workspace_slug := 'u-' || replace(NEW.id::text, '-', '');
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.workspace_slug IS DISTINCT FROM OLD.workspace_slug THEN
        RAISE EXCEPTION 'identity workspace slug is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_identity_user_default_workspace_slug
    BEFORE INSERT ON yumpoo.identity_user
    FOR EACH ROW EXECUTE FUNCTION yumpoo.protect_identity_workspace_slug();

CREATE TRIGGER trg_identity_user_workspace_slug_immutable
    BEFORE UPDATE OF workspace_slug ON yumpoo.identity_user
    FOR EACH ROW EXECUTE FUNCTION yumpoo.protect_identity_workspace_slug();

COMMENT ON COLUMN yumpoo.identity_user.workspace_slug IS
    'Immutable current-user workspace URL segment; presentation identity only and never an authorization boundary.';
