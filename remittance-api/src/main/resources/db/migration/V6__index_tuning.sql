-- Day 11 인덱스 튜닝 (자세한 사례는 docs/04-erd.md "인덱스 튜닝 사례" 참고)
--
-- 추가 동기:
--   RemittanceRepository.findByUserIdOrderByCreatedAtDesc(userId)
--   → WHERE user_id = ? ORDER BY created_at DESC
--   기존 (user_id, status) 인덱스로는 user_id 필터까지만 인덱스가 도와주고
--   ORDER BY created_at DESC 단계에서 filesort 발생.
--
-- (user_id, created_at) 만으로는 status 필터 쿼리(idx_remittances_user_status)와 둘 다 필요.
-- 그래서 기존 인덱스를 유지하고 (user_id, created_at)을 추가한다.
-- created_at 자체를 DESC로 만들 필요는 없다 — InnoDB는 backward index scan 가능.

ALTER TABLE remittances
    ADD INDEX idx_remittances_user_created (user_id, created_at);
