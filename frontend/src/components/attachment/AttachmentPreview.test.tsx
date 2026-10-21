import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { AttachmentPreview } from './AttachmentPreview';
import { AttachmentLightbox } from './AttachmentLightbox';
import { getFileInfo, isImage, decodeFilename } from './fileIcon';
import '@testing-library/jest-dom';

vi.mock('axios', () => ({
  default: {
    post: vi.fn(),
    get: vi.fn(),
  },
}));

describe('fileIcon helpers', () => {
  it('should return correct label for image extension', () => {
    const info = getFileInfo('photo.png');
    expect(info.label).toBe('图片');
    expect(info.category).toBe('image');
  });

  it('should return correct label for pdf extension', () => {
    const info = getFileInfo('document.pdf');
    expect(info.label).toBe('文档');
    expect(info.category).toBe('doc');
  });

  it('should return correct label for archive extension', () => {
    const info = getFileInfo('archive.zip');
    expect(info.label).toBe('压缩包');
    expect(info.category).toBe('archive');
  });

  it('should return other for unknown extension', () => {
    const info = getFileInfo('unknown.xyz');
    expect(info.label).toBe('文件');
    expect(info.category).toBe('other');
  });

  it('should check if file is image', () => {
    expect(isImage('photo.jpg')).toBe(true);
    expect(isImage('chart.png')).toBe(true);
    expect(isImage('document.pdf')).toBe(false);
  });

  it('should extract filename from storage key', () => {
    expect(decodeFilename('tenant-123/file.jpg')).toBe('file.jpg');
    expect(decodeFilename('tenant-123/path/to/doc.pdf')).toBe('doc.pdf');
    expect(decodeFilename('justname.png')).toBe('justname.png');
  });
});

describe('AttachmentLightbox', () => {
  const onClose = vi.fn();

  beforeEach(() => {
    onClose.mockClear();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('should render lightbox content when open', () => {
    const testKey = 'tenant-123/photo.jpg';
    render(<AttachmentLightbox open={true} onClose={onClose} storageKey={testKey} />);

    expect(screen.getByRole('dialog')).toBeInTheDocument();
    // At minimum the dialog with buttons should be present
    expect(screen.getByRole('button', { name: /关闭/i })).toBeInTheDocument();
  });

  it('should not render when closed', () => {
    render(<AttachmentLightbox open={false} onClose={onClose} storageKey="tenant-123/photo.jpg" />);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

describe('AttachmentPreview', () => {
  const mockOnChange = vi.fn();

  beforeEach(() => {
    mockOnChange.mockClear();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('should render readonly mode with thumbnail', () => {
    render(
      <AttachmentPreview
        value="tenant-123/file.jpg"
        mode="readonly"
        onChange={mockOnChange}
      />
    );

    // Should show the file name
    expect(screen.getByText(/file\.jpg/i)).toBeInTheDocument();
    // Should have image element (for thumbnail)
    const img = screen.getByRole('img');
    expect(img).toBeInTheDocument();
    // Delete button should NOT be present in readonly mode
    expect(screen.queryByRole('button', { name: /删除/i })).not.toBeInTheDocument();
  });

  it('should render edit mode with upload placeholder when no value', () => {
    render(
      <AttachmentPreview
        mode="edit"
        onChange={mockOnChange}
      />
    );

    // Upload area should be visible
    expect(screen.getByText(/点击或拖拽文件上传/i)).toBeInTheDocument();
  });

  it('should show delete button in edit mode', () => {
    render(
      <AttachmentPreview
        value="tenant-123/document.pdf"
        mode="edit"
        onChange={mockOnChange}
      />
    );

    expect(screen.getByRole('button', { name: /删除/i })).toBeInTheDocument();
  });

  it('should call onChange with empty string on delete', async () => {
    render(
      <AttachmentPreview
        value="tenant-123/file.jpg"
        mode="edit"
        onChange={mockOnChange}
      />
    );

    const deleteBtn = screen.getByRole('button', { name: /删除/i });
    fireEvent.click(deleteBtn);

    await waitFor(() => {
      expect(mockOnChange).toHaveBeenCalledWith('');
    });
  });

  it('should not call onChange in readonly mode', () => {
    render(
      <AttachmentPreview
        value="tenant-123/file.jpg"
        mode="readonly"
        onChange={mockOnChange}
      />
    );

    // No delete button in readonly mode
    expect(screen.queryByRole('button', { name: /删除/i })).not.toBeInTheDocument();
    expect(mockOnChange).not.toHaveBeenCalled();
  });

  it('should render with different sizes', () => {
    const { rerender } = render(
      <AttachmentPreview
        value="tenant-123/file.jpg"
        mode="readonly"
        size="sm"
        onChange={mockOnChange}
      />
    );

    // Should render without error
    expect(screen.getByText(/file\.jpg/i)).toBeInTheDocument();

    rerender(
      <AttachmentPreview
        value="tenant-123/file.jpg"
        mode="readonly"
        size="lg"
        onChange={mockOnChange}
      />
    );

    expect(screen.getByText(/file\.jpg/i)).toBeInTheDocument();
  });
});
