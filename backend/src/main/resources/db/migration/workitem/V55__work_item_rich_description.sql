-- Work item descriptions become restricted rich-text HTML; wrap legacy plain text lines as escaped paragraphs.
UPDATE yumpoo.work_item
SET description = '<p>' || replace(
        replace(replace(replace(replace(replace(description, E'\r\n', E'\n'), E'\r', E'\n'),
            '&', '&amp;'), '<', '&lt;'), '>', '&gt;'),
        E'\n', '</p><p>') || '</p>'
WHERE description IS NOT NULL;
