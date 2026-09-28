"""文档模型 — 用于 RAG 检索的向量存储."""

from sqlalchemy import Column, String, Text, ForeignKey
from sqlalchemy.dialects.postgresql import UUID, ARRAY, FLOAT
from sqlalchemy.orm import declarative_base, relationship

Base = declarative_base()


class Document(Base):
    """存储文档的标题、内容及其向量嵌入."""
    __tablename__ = "documents"

    id = Column(UUID(as_uuid=True), primary_key=True, server_default=text("gen_random_uuid()"))
    tenant_id = Column(String(64), nullable=False, index=True)
    kb_id = Column(UUID(as_uuid=True), ForeignKey("knowledge_bases.id"), nullable=True)
    title = Column(String(255), nullable=False)
    content = Column(Text, nullable=False)
    # 向量列：PostgreSQL pgvector 需要 vector 类型
    # 注意：实际使用时需确保 pgvector 扩展已启用
    embedding = Column(ARRAY(FLOAT), nullable=True)  # 临时用 ARRAY，后期迁移至 vector
    
    # 时间戳
    created_at = Column(String, nullable=False)
    updated_at = Column(String, nullable=False)

    def __repr__(self) -> str:
        return f"<Document id={self.id} title={self.title!r}>"