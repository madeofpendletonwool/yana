-- Aliases (Phase 29). A note answers to the names in its frontmatter
-- `aliases` list besides its path and filename, and a link and an embed
-- of the same target from one note are two different references, so the
-- links table grows a kind column in its key.

CREATE TABLE aliases (
    note_id TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    space   TEXT NOT NULL,
    alias   TEXT NOT NULL,
    PRIMARY KEY (note_id, alias)
);
CREATE INDEX aliases_lookup ON aliases(space, alias);

CREATE TABLE links_new (
    from_id    TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    to_id      TEXT,                -- NULL when unresolved
    raw_target TEXT NOT NULL,
    kind       TEXT NOT NULL DEFAULT 'link', -- 'link' or 'embed'
    resolved   INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (from_id, kind, raw_target)
);
INSERT INTO links_new (from_id, to_id, raw_target, kind, resolved)
    SELECT from_id, to_id, raw_target, 'link', resolved FROM links;
DROP TRIGGER links_target_deleted;
DROP TABLE links;
ALTER TABLE links_new RENAME TO links;
CREATE INDEX links_to ON links(to_id, resolved);
CREATE INDEX links_unresolved ON links(resolved) WHERE resolved = 0;
CREATE TRIGGER links_target_deleted AFTER DELETE ON notes BEGIN
    UPDATE links SET to_id = NULL, resolved = 0 WHERE to_id = old.id AND resolved = 1;
END;
