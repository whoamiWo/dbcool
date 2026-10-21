import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import ConflictResolver from './ConflictResolver';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, params?: Record<string, unknown>) =>
      params ? `${key}:${JSON.stringify(params)}` : key,
  }),
}));

vi.mock('@/api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

vi.mock('@/features/realtime/CollabEditor', () => ({}));

import apiClient from '@/api/client';

const mockGet = vi.mocked(apiClient.get);
const mockPost = vi.mocked(apiClient.post);

const conflictPayload = {
  code: 0,
  data: {
    docId: 'doc-123',
    currentVersion: 5,
    since: 0,
    conflicting: true,
    versions: [
      { version: 3, createdAt: '2026-10-20T10:00:00Z', size: 3072 },
      { version: 4, createdAt: '2026-10-20T10:01:00Z', size: 4096 },
    ],
  },
};

describe('ConflictResolver', () => {
  const baseProps = {
    docId: 'doc-123',
    remoteText: 'remote content',
    localOriginalText: 'local original',
    onResolved: vi.fn(),
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('does not fetch when remoteText equals localOriginalText', () => {
    render(
      <ConflictResolver
        {...baseProps}
        remoteText="same"
        localOriginalText="same"
      />,
    );
    expect(mockGet).not.toHaveBeenCalled();
  });

  it('fetches conflicts and opens the dialog when texts differ', async () => {
    mockGet.mockResolvedValue(conflictPayload as never);

    render(<ConflictResolver {...baseProps} />);

    await waitFor(() => {
      expect(mockGet).toHaveBeenCalledWith(
        '/crdt/conflicts/doc-123',
        expect.objectContaining({ params: { since: 0 } }),
      );
    });

    expect(await screen.findByText('conflict.title')).toBeInTheDocument();
  });

  it('renders version history entries with formatted sizes', async () => {
    mockGet.mockResolvedValue(conflictPayload as never);

    render(<ConflictResolver {...baseProps} />);

    expect(await screen.findByText('conflict.title')).toBeInTheDocument();
    expect(screen.getByText('conflict.versionLabel:{"version":3}')).toBeInTheDocument();
    expect(screen.getByText('3.0 KB')).toBeInTheDocument();
    expect(screen.getByText('4.0 KB')).toBeInTheDocument();
  });

  it('shows the conflict alert with version counts', async () => {
    mockGet.mockResolvedValue(conflictPayload as never);

    render(<ConflictResolver {...baseProps} />);

    expect(
      await screen.findByText(
        'conflict.alert:{"currentVersion":5,"since":0,"conflicts":2}',
      ),
    ).toBeInTheDocument();
  });

  it('calls onResolved when applying remote version', async () => {
    mockGet.mockResolvedValue(conflictPayload as never);

    render(<ConflictResolver {...baseProps} />);

    const applyBtn = await screen.findByText('conflict.applyRemote');
    fireEvent.click(applyBtn);

    expect(baseProps.onResolved).toHaveBeenCalledTimes(1);
  });

  it('calls rollback API and onResolved when rolling back a version', async () => {
    mockGet.mockResolvedValue(conflictPayload as never);
    mockPost.mockResolvedValue({ code: 0, data: {} } as never);

    render(<ConflictResolver {...baseProps} />);

    const versionItem = await screen.findByText(
      'conflict.versionLabel:{"version":3}',
    );
    fireEvent.click(versionItem);

    const rollbackBtn = await screen.findByText(
      'conflict.rollbackTo:{"version":3}',
    );
    fireEvent.click(rollbackBtn);

    await waitFor(() => {
      expect(mockPost).toHaveBeenCalledWith('/crdt/rollback/doc-123', {
        version: 3,
      });
    });
    await waitFor(() => {
      expect(baseProps.onResolved).toHaveBeenCalled();
    });
  });

  it('shows an error snackbar when fetching fails', async () => {
    mockGet.mockRejectedValue(new Error('network down'));

    render(<ConflictResolver {...baseProps} />);

    expect(await screen.findByText('conflict.fetchFailed')).toBeInTheDocument();
  });
});
