CREATE TABLE test_plan_covered_rule (
    id               BIGSERIAL PRIMARY KEY,
    test_plan_id     BIGINT NOT NULL REFERENCES test_plan(id) ON DELETE CASCADE,
    business_rule_id BIGINT NOT NULL REFERENCES business_rule(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_test_plan_covered_rule ON test_plan_covered_rule(test_plan_id, business_rule_id);
CREATE INDEX idx_test_plan_covered_rule_plan ON test_plan_covered_rule(test_plan_id);
CREATE INDEX idx_test_plan_covered_rule_rule ON test_plan_covered_rule(business_rule_id);

INSERT INTO test_plan_covered_rule(test_plan_id, business_rule_id)
SELECT id, business_rule_id
FROM test_plan;

DROP VIEW IF EXISTS v_traceability;

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
LEFT JOIN test_plan_covered_rule tpcr ON tpcr.business_rule_id = br.id
LEFT JOIN test_plan tp ON tp.id = tpcr.test_plan_id
LEFT JOIN test_case tc ON tc.test_plan_id = tp.id
LEFT JOIN unit_test ut ON ut.test_case_id = tc.id;
