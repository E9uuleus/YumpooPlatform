ALTER TABLE yumpoo.project_work_item_status_label
    DROP CONSTRAINT ck_project_work_item_status_label_color,
    ADD CONSTRAINT ck_project_work_item_status_label_color CHECK (
        color_token IN (
            'BRIGHT_GREEN', 'SALADISH', 'EGG_YOLK', 'DARK_ORANGE',
            'PEACH', 'SUNSET', 'DARK_RED', 'SOFIA_PINK', 'LIPSTICK', 'BUBBLE',
            'DARK_PURPLE', 'BERRY', 'DARK_INDIGO', 'INDIGO', 'NAVY', 'BRIGHT_BLUE',
            'AQUAMARINE', 'CHILI_BLUE', 'RIVER', 'WINTER', 'AMERICAN_GRAY', 'BLACKISH',
            'BROWN', 'ORCHID', 'TAN', 'SKY', 'COFFEE', 'ROYAL', 'TEAL', 'LAVENDER',
            'STEEL', 'LILAC', 'PECAN',
            'GREEN', 'BLUE', 'PURPLE', 'MAGENTA', 'RED', 'ORANGE', 'AMBER', 'LIME',
            'CYAN', 'GRAY'
        )
    );

ALTER TABLE yumpoo.project_work_item_priority_label
    DROP CONSTRAINT ck_project_work_item_priority_label_color,
    ADD CONSTRAINT ck_project_work_item_priority_label_color CHECK (
        color_token IN (
            'BRIGHT_GREEN', 'SALADISH', 'EGG_YOLK', 'DARK_ORANGE',
            'PEACH', 'SUNSET', 'DARK_RED', 'SOFIA_PINK', 'LIPSTICK', 'BUBBLE',
            'DARK_PURPLE', 'BERRY', 'DARK_INDIGO', 'INDIGO', 'NAVY', 'BRIGHT_BLUE',
            'AQUAMARINE', 'CHILI_BLUE', 'RIVER', 'WINTER', 'AMERICAN_GRAY', 'BLACKISH',
            'BROWN', 'ORCHID', 'TAN', 'SKY', 'COFFEE', 'ROYAL', 'TEAL', 'LAVENDER',
            'STEEL', 'LILAC', 'PECAN',
            'GREEN', 'BLUE', 'PURPLE', 'MAGENTA', 'RED', 'ORANGE', 'AMBER', 'LIME',
            'CYAN', 'GRAY'
        )
    );
