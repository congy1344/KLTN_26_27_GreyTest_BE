ALTER TABLE project
    ADD COLUMN total_production_files INTEGER,
    ADD COLUMN parsed_production_files INTEGER,
    ADD COLUMN failed_parse_files INTEGER,
    ADD COLUMN failed_parse_file_paths JSONB;
