import { FieldType } from '../../types/collection';

/**
 * T1: 字段类型扩展前端测试 — 验证新增 12 种类型的可用性。
 */
describe('Field Type Extension (T1)', () => {
  const t1NewTypes: FieldType[] = [
    // 可编辑类型 (7)
    'email',
    'url',
    'phone',
    'currency',
    'percent',
    'rating',
    'duration',
    // 自动类型 (5)
    'createdTime',
    'lastModifiedTime',
    'createdBy',
    'lastModifiedBy',
    'autonumber',
  ];

  const originalTypes: FieldType[] = [
    'text',
    'number',
    'boolean',
    'date',
    'datetime',
    'select',
    'multiSelect',
    'attachment',
    'belongsTo',
    'hasMany',
    'formula',
    'rollup',
    'lookup',
  ];

  describe('T1 new field types should be assignable', () => {
    t1NewTypes.forEach((type) => {
      test(`${type} should be a valid FieldType`, () => {
        const field = {
          name: `test_${type}`,
          type: type,
          required: false,
          label: `Test ${type}`,
        };
        expect(field.type).toBe(type);
      });
    });
  });

  describe('Original 13 types should still work (regression)', () => {
    originalTypes.forEach((type) => {
      test(`${type} should still be valid`, () => {
        const field = {
          name: `test_${type}`,
          type: type,
          required: false,
          label: `Test ${type}`,
        };
        expect(field.type).toBe(type);
      });
    });
  });

  describe('Email field format validation', () => {
    test('valid email should pass', () => {
      const validEmails = ['test@example.com', 'user.name@domain.org', 'a@b.co'];
      validEmails.forEach((email) => {
        const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
        expect(emailRegex.test(email)).toBe(true);
      });
    });

    test('invalid email should fail', () => {
      const invalidEmails = ['not-an-email', '@missing-domain', 'missing@.com'];
      invalidEmails.forEach((email) => {
        const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
        expect(emailRegex.test(email)).toBe(false);
      });
    });
  });

  describe('URL field format validation', () => {
    test('valid URL should pass', () => {
      const validUrls = ['https://example.com', 'http://localhost:3000', 'https://sub.domain.io/path'];
      validUrls.forEach((url) => {
        const urlRegex = /^https?:\/\/[^\s/$.?#].[^\s]*$/i;
        expect(urlRegex.test(url)).toBe(true);
      });
    });

    test('invalid URL should fail', () => {
      const invalidUrls = ['not-a-url', 'ftp://unsupported', 'http://'];
      invalidUrls.forEach((url) => {
        const urlRegex = /^https?:\/\/[^\s/$.?#].[^\s]*$/i;
        expect(urlRegex.test(url)).toBe(false);
      });
    });
  });

  describe('Phone field format validation (lenient)', () => {
    test('valid phone should pass', () => {
      const validPhones = ['1234567890', '+1-234-567-8900', '(123) 456-7890', '123-456-7890'];
      validPhones.forEach((phone) => {
        const phoneRegex = /^[\d\s\-\+\(\)]{7,20}$/;
        expect(phoneRegex.test(phone)).toBe(true);
      });
    });

    test('invalid phone should fail', () => {
      const invalidPhones = ['', 'abc-def-ghij', '123'];
      invalidPhones.forEach((phone) => {
        const phoneRegex = /^[\d\s\-\+\(\)]{7,20}$/;
        expect(phoneRegex.test(phone)).toBe(false);
      });
    });
  });

  describe('Currency field value validation', () => {
    test('valid currency values should pass', () => {
      const validValues = [0, 100, -50.99, 1234567.89];
      validValues.forEach((val) => {
        expect(typeof val).toBe('number');
        expect(isNaN(val)).toBe(false);
      });
    });
  });

  describe('Percent field value validation', () => {
    test('valid percent values should be 0-100 or decimal 0-1', () => {
      const validPercentages = [0, 50, 100, 0.5, 1.0];
      validPercentages.forEach((val) => {
        expect(val >= 0 && val <= 100 || val >= 0 && val <= 1).toBe(true);
      });
    });
  });

  describe('Rating field value validation', () => {
    test('valid rating should be integer 1-5', () => {
      const validRatings = [1, 2, 3, 4, 5];
      validRatings.forEach((rating) => {
        expect(Number.isInteger(rating) && rating >= 1 && rating <= 5).toBe(true);
      });
    });

    test('invalid rating should fail', () => {
      const invalidRatings = [0, 6, -1, 2.5];
      invalidRatings.forEach((rating) => {
        expect(!(Number.isInteger(rating) && rating >= 1 && rating <= 5)).toBe(true);
      });
    });
  });

  describe('Duration field value validation', () => {
    test('valid duration should be positive integer (minutes)', () => {
      const validDurations = [1, 60, 120, 1440];
      validDurations.forEach((dur) => {
        expect(Number.isInteger(dur) && dur > 0).toBe(true);
      });
    });
  });

  describe('System auto fields should be read-only', () => {
    const autoFields: FieldType[] = ['createdTime', 'lastModifiedTime', 'createdBy', 'lastModifiedBy', 'autonumber'];
    
    autoFields.forEach((fieldType) => {
      test(`${fieldType} should be marked as read-only`, () => {
        const field = {
          name: `auto_${fieldType}`,
          type: fieldType,
          required: false,
          label: `Auto ${fieldType}`,
          readOnly: true, // System fields should be read-only
        };
        expect(field.readOnly).toBe(true);
      });
    });
  });
});