-- Traceability Matrix là view ảo dựng từ các bảng BR -> Plan -> Case -> UnitTest.
CREATE VIEW v_traceability AS
SELECT
    br.id               AS rule_id,
    br.rule_code        AS rule_code,
    br.description      AS rule_description,
    tp.id               AS plan_id,
    tp.plan_code        AS plan_code,
    tp.title            AS plan_title,
    tp.test_type        AS test_type,
    tc.id               AS case_id,
    tc.case_code        AS case_code,
    tc.description      AS case_description,
    ut.id               AS unit_test_id,
    ut.test_method_name AS unit_test_name,
    br.project_id       AS project_id
FROM business_rule br
LEFT JOIN test_plan tp ON tp.business_rule_id = br.id
LEFT JOIN test_case tc ON tc.test_plan_id = tp.id
LEFT JOIN unit_test ut ON ut.test_case_id = tc.id;
