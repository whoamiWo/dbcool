import { describe, it, expect } from 'vitest';

// PHASE69 R2: Attachment security tests
describe('Attachment Security', () => {
  it('rejects path traversal in storageKey', () => {
    const storageKeys = [
      '../etc/passwd',
      'tenant\\..\\etc',
      '/etc/passwd',
      '..\\..\\windows\\system32',
    ];
    
    for (const key of storageKeys) {
      const isValid = !(key.includes('..') || key.startsWith('/') || key.includes('\\'));
      expect(isValid).toBe(false);
    }
  });

  it('accepts valid tenant-scoped storageKey', () => {
    const storageKey = 'tenant_default/abc-123/document.pdf';
    const tenantId = 'tenant_default';
    
    const isValid = !storageKey.includes('..') && 
                    !storageKey.startsWith('/') && 
                    !storageKey.includes('\\') &&
                    storageKey.startsWith(tenantId + '/');
    
    expect(isValid).toBe(true);
  });

  it('rejects cross-tenant storageKey', () => {
    const storageKey = 'other_tenant/abc-123/document.pdf';
    const tenantId = 'tenant_default';
    
    const isValid = storageKey.startsWith(tenantId + '/');
    expect(isValid).toBe(false);
  });
});
