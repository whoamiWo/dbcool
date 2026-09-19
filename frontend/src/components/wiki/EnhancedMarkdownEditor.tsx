import type { WikiPage } from '@/types/wiki';
import { NotionStyleEditor } from './NotionStyleEditor';

interface EnhancedMarkdownEditorProps {
  page?: WikiPage;
  title: string;
  onTitleChange: (title: string) => void;
  content: string;
  onContentChange: (content: string) => void;
  _page?: WikiPage;
}

/**
 * EnhancedMarkdownEditor — 兼容旧 API 的包装器。
 *
 * <p>Week 45 升级:底层委托给 {@link NotionStyleEditor}（Notion 风格块编辑器），
 * 保留原有 props 接口，使 WikiPageEdit 无需改动即可获得块级编辑体验。
 */
export function EnhancedMarkdownEditor({
  page: _page,
  title,
  onTitleChange,
  content,
  onContentChange,
}: EnhancedMarkdownEditorProps) {
  return (
    <NotionStyleEditor
      page={_page}
      title={title}
      onTitleChange={onTitleChange}
      content={content}
      onContentChange={onContentChange}
    />
  );
}