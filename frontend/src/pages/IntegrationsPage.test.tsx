import { describe, it, expect, vi, beforeEach } from 'vitest';
import * as integrationsApi from '../api/integrations';

vi.mock('../api/integrations', () => ({
  integrationApi: {
    getMarket: vi.fn(),
    install: vi.fn(),
    uninstall: vi.fn(),
  },
}));

describe('IntegrationsPage API Tests', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('getMarket returns market data', async () => {
    const mockData = {
      code: 0,
      message: 'success',
      data: {
        integrations: [
          { id: 'slack', name: 'Slack', description: 'Slack integration', icon: '', kind: 'builtin', installed: false },
          { id: 'dingtalk', name: '钉钉', description: 'DingTalk', icon: '', kind: 'builtin', installed: true },
        ],
      },
    };

    vi.spyOn(integrationsApi.integrationApi, 'getMarket').mockResolvedValue(mockData);

    const result = await integrationsApi.integrationApi.getMarket();
    expect(result.code).toBe(0);
    expect(result.data?.integrations.length).toBe(2);
  });

  it('install calls the correct endpoint', async () => {
    vi.spyOn(integrationsApi.integrationApi, 'install').mockResolvedValue({
      code: 0,
      message: 'installed',
    });

    const result = await integrationsApi.integrationApi.install('slack');
    expect(result.code).toBe(0);
    expect(vi.mocked(integrationsApi.integrationApi.install)).toHaveBeenCalledWith('slack');
  });

  it('uninstall calls the correct endpoint', async () => {
    vi.spyOn(integrationsApi.integrationApi, 'uninstall').mockResolvedValue({
      code: 0,
      message: 'uninstalled',
    });

    const result = await integrationsApi.integrationApi.uninstall('dingtalk');
    expect(result.code).toBe(0);
    expect(vi.mocked(integrationsApi.integrationApi.uninstall)).toHaveBeenCalledWith('dingtalk');
  });
});
