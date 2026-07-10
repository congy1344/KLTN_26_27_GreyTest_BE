WITH numbered AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY project_id ORDER BY id) AS number
    FROM business_rule
)
UPDATE business_rule br
SET rule_code = 'BR-' || LPAD(numbered.number::TEXT, 3, '0')
FROM numbered
WHERE br.id = numbered.id;

WITH numbered AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY project_id ORDER BY id) AS number
    FROM test_plan
)
UPDATE test_plan tp
SET plan_code = 'TP-' || LPAD(numbered.number::TEXT, 3, '0')
FROM numbered
WHERE tp.id = numbered.id;

CREATE UNIQUE INDEX uq_business_rule_project_code ON business_rule(project_id, rule_code);
CREATE UNIQUE INDEX uq_test_plan_project_code ON test_plan(project_id, plan_code);
