-- 全新数据目录时由 postgres docker-entrypoint-initdb.d 执行（仅空卷）。
-- 兜底扩展：即使 Flyway V1 因 baseline 被跳过，uuid-ossp / vector 也能就绪。
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "vector";
