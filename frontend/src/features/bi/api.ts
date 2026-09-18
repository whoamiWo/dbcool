import apiClient from '@/api/client';

/** BI 过滤规则(与后端 CollectionService.FilterRule 语义一致) */
export interface BiFilter {
  field: string;
  op: 'eq' | 'neq' | 'contains' | 'gt' | 'lt' | 'empty' | 'notEmpty';
  value?: unknown;
}

/** 度量:字段 + 聚合函数 */
export interface BiMeasure {
  field: string;
  agg: 'SUM' | 'COUNT' | 'AVG' | 'MIN' | 'MAX';
}

export interface PivotRequest {
  collection: string;
  rows?: string[];
  columns?: string[];
  values?: BiMeasure[];
  filters?: BiFilter[];
}

export interface PivotResult {
  rows: string[];
  columns: string[];
  values: string[];
  data: Array<Record<string, unknown>>;
  totalRows: number;
  totalCols: number;
}

export interface ChartRequest {
  collection: string;
  chartType?: 'bar' | 'line' | 'pie' | 'area';
  xField: string;
  yField: string;
  seriesField?: string;
  agg?: string;
  filters?: BiFilter[];
}

export interface ChartSeries {
  name: string;
  data: number[];
}

export interface ChartResult {
  chartType: string;
  categories: string[];
  series: ChartSeries[];
  total: number;
}

interface Envelope<T> {
  code: number;
  message: string;
  data: T;
}

/** 后端返回 {code, message, data};取 data。 */
function unwrap<T>(r: unknown): T {
  if (r && typeof r === 'object' && 'data' in (r as Record<string, unknown>)) {
    return (r as Envelope<T>).data;
  }
  return r as T;
}

export const biApi = {
  /** 数据透视(下推 SQL 聚合) */
  pivot: async (body: PivotRequest): Promise<PivotResult> => {
    const r = await apiClient.post<Envelope<PivotResult>>('/bi/pivot', body);
    return unwrap<PivotResult>(r);
  },

  /** 图表数据(下推 SQL 聚合) */
  chart: async (body: ChartRequest): Promise<ChartResult> => {
    const r = await apiClient.post<Envelope<ChartResult>>('/bi/chart', body);
    return unwrap<ChartResult>(r);
  },

  /** 已保存报表定义 */
  listReports: async (collectionName: string) => {
    const r = await apiClient.get<Envelope<unknown[]>>('/bi/reports', {
      params: { collectionName },
    });
    return unwrap<unknown[]>(r);
  },

  /** 保存报表定义 */
  saveReport: async (body: Record<string, unknown>) => {
    const r = await apiClient.post<Envelope<unknown>>('/bi/reports', body);
    return unwrap<unknown>(r);
  },
};
