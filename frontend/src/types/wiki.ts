export interface KnowledgeBase {
  id: string;
  tenant_id: string;
  name: string;
  description: string | null;
  slug: string;
  icon: string | null;
  sort_order: number;
  created_by: string | null;
  created_at: string;
  updated_at: string;
}

export interface WikiPage {
  id: string;
  tenant_id: string;
  knowledge_base_id: string;
  parent_id: string | null;
  title: string;
  slug: string;
  content: string;
  content_html: string | null;
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
  version: number;
  created_by: string | null;
  updated_by: string | null;
  created_at: string;
  updated_at: string;
}

export interface WikiVersion {
  id: string;
  wiki_page_id: string;
  version: number;
  content: string;
  summary: string | null;
  created_by: string | null;
  created_at: string;
}

export interface WikiCategory {
  id: string;
  tenant_id: string;
  knowledge_base_id: string;
  parent_id: string | null;
  name: string;
  slug: string;
  sort_order: number;
  created_at: string;
  updated_at: string;
}

export interface SearchResult {
  id: string;
  title: string;
  slug: string;
  snippet: string | null;
  rank: number;
  updated_at: string;
}