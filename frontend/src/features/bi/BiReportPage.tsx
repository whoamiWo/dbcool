import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Box, Button, Card, CardContent, Chip, CircularProgress, Divider, FormControl,
  Grid, InputLabel, MenuItem, Select, Stack, TextField, Typography, Alert,
} from '@mui/material';
import {
  BarChart, Bar, LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip,
  Legend, ResponsiveContainer,
} from 'recharts';
import apiClient from '@/api/client';
import { biApi, type BiFilter, type BiMeasure } from './api';

interface FieldMeta { name: string; type: string }

interface CollectionMeta {
  name: string;
  title?: string;
  fieldsJson?: string;
  fields?: FieldMeta[];
}

const AGGS: Array<BiMeasure['agg']> = ['SUM', 'COUNT', 'AVG', 'MIN', 'MAX'];
const CHART_TYPES = ['bar', 'line'] as const;

/** 解析 collection 的字段定义(后端存 fieldsJson 字符串)。 */
function parseFields(c?: CollectionMeta): FieldMeta[] {
  if (!c) return [];
  if (Array.isArray(c.fields)) return c.fields;
  try {
    const raw = JSON.parse(c.fieldsJson || '[]');
    return Array.isArray(raw) ? raw : [];
  } catch {
    return [];
  }
}

/** BI 报表与数据透视页 — 聚合由后端下推至物理表 SQL 完成。 */
export default function BiReportPage() {
  const [collection, setCollection] = useState('');
  const [rows, setRows] = useState<string[]>([]);
  const [columns, setColumns] = useState<string[]>([]);
  const [measureField, setMeasureField] = useState('');
  const [agg, setAgg] = useState<BiMeasure['agg']>('SUM');
  const [chartType, setChartType] = useState<(typeof CHART_TYPES)[number]>('bar');
  const [filterField, setFilterField] = useState('');
  const [filterOp, setFilterOp] = useState<BiFilter['op']>('eq');
  const [filterValue, setFilterValue] = useState('');
  const [submitted, setSubmitted] = useState(false);

  const { data: collectionsResp, isLoading: loadingCols } = useQuery({
    queryKey: ['collections'],
    queryFn: () => apiClient.get<{ code: number; data: CollectionMeta[] }>('/collections'),
  });

  const collections: CollectionMeta[] = useMemo(() => {
    const r = collectionsResp as unknown;
    if (Array.isArray(r)) return r as CollectionMeta[];
    return ((r as { data?: CollectionMeta[] })?.data ?? []) as CollectionMeta[];
  }, [collectionsResp]);

  const fields = useMemo(
    () => parseFields(collections.find((c) => c.name === collection)),
    [collections, collection],
  );
  const numericFields = fields.filter((f) =>
    ['number', 'formula'].includes(f.type),
  );

  const filters: BiFilter[] = useMemo(() => {
    if (!filterField) return [];
    return [{ field: filterField, op: filterOp, value: filterValue }];
  }, [filterField, filterOp, filterValue]);

  const values: BiMeasure[] = measureField ? [{ field: measureField, agg }] : [];
  const canQuery = !!collection && (rows.length > 0 || columns.length > 0) && !!measureField;

  const pivotQ = useQuery({
    queryKey: ['bi-pivot', collection, rows, columns, values, filters],
    queryFn: () => biApi.pivot({ collection, rows, columns, values, filters }),
    enabled: submitted && canQuery,
  });

  const chartQ = useQuery({
    queryKey: ['bi-chart', collection, rows, measureField, agg, filters, chartType],
    queryFn: () => biApi.chart({
      collection,
      chartType,
      xField: rows[0] ?? '',
      yField: measureField,
      seriesField: columns[0],
      agg,
      filters,
    }),
    enabled: submitted && canQuery && !!measureField && !!rows[0],
  });

  const chartData = useMemo(() => {
    const res = chartQ.data;
    if (!res) return [];
    return res.categories.map((c, i) => {
      const row: Record<string, unknown> = { name: c };
      res.series.forEach((s) => { row[s.name] = s.data[i] ?? 0; });
      return row;
    });
  }, [chartQ.data]);

  const run = () => setSubmitted(true);

  if (loadingCols) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}>
        <CircularProgress />
      </Box>
    );
  }

  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h5" sx={{ fontWeight: 600, mb: 2 }}>
        📊 数据透视与报表
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        选择数据集与维度，聚合在数据库层完成后返回。
      </Typography>

      <Card sx={{ mb: 3 }}>
        <CardContent>
          <Grid container spacing={2}>
            <Grid size={{ xs: 12, md: 3 }}>
              <FormControl fullWidth size="small">
                <InputLabel>数据集</InputLabel>
                <Select
                  value={collection}
                  label="数据集"
                  onChange={(e) => {
                    setCollection(e.target.value);
                    setSubmitted(false);
                  }}
                >
                  {collections.map((c) => (
                    <MenuItem key={c.name} value={c.name}>
                      {c.title || c.name}
                    </MenuItem>
                  ))}
                </Select>
              </FormControl>
            </Grid>

            <Grid size={{ xs: 12, md: 3 }}>
              <FormControl fullWidth size="small">
                <InputLabel>行维度</InputLabel>
                <Select
                  value={rows[0] ?? ''}
                  label="行维度"
                  onChange={(e) => setRows(e.target.value ? [e.target.value] : [])}
                >
                  <MenuItem value="">无</MenuItem>
                  {fields.map((f) => (
                    <MenuItem key={f.name} value={f.name}>{f.name}</MenuItem>
                  ))}
                </Select>
              </FormControl>
            </Grid>

            <Grid size={{ xs: 12, md: 3 }}>
              <FormControl fullWidth size="small">
                <InputLabel>列维度（系列）</InputLabel>
                <Select
                  value={columns[0] ?? ''}
                  label="列维度（系列）"
                  onChange={(e) => setColumns(e.target.value ? [e.target.value] : [])}
                >
                  <MenuItem value="">无</MenuItem>
                  {fields.map((f) => (
                    <MenuItem key={f.name} value={f.name}>{f.name}</MenuItem>
                  ))}
                </Select>
              </FormControl>
            </Grid>

            <Grid size={{ xs: 6, md: 1.5 }}>
              <FormControl fullWidth size="small">
                <InputLabel>度量</InputLabel>
                <Select
                  value={measureField}
                  label="度量"
                  onChange={(e) => setMeasureField(e.target.value)}
                >
                  {(numericFields.length ? numericFields : fields).map((f) => (
                    <MenuItem key={f.name} value={f.name}>{f.name}</MenuItem>
                  ))}
                </Select>
              </FormControl>
            </Grid>

            <Grid size={{ xs: 6, md: 1.5 }}>
              <FormControl fullWidth size="small">
                <InputLabel>聚合</InputLabel>
                <Select value={agg} label="聚合"
                  onChange={(e) => setAgg(e.target.value as BiMeasure['agg'])}>
                  {AGGS.map((a) => <MenuItem key={a} value={a}>{a}</MenuItem>)}
                </Select>
              </FormControl>
            </Grid>

            <Grid size={{ xs: 12 }}>
              <Divider sx={{ my: 1 }} />
              <Stack direction="row" spacing={2}
                sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
                <Typography variant="body2" color="text.secondary">筛选：</Typography>
                <FormControl size="small" sx={{ minWidth: 140 }}>
                  <InputLabel>字段</InputLabel>
                  <Select value={filterField} label="字段"
                    onChange={(e) => setFilterField(e.target.value)}>
                    <MenuItem value="">无</MenuItem>
                    {fields.map((f) => (
                      <MenuItem key={f.name} value={f.name}>{f.name}</MenuItem>
                    ))}
                  </Select>
                </FormControl>
                <FormControl size="small" sx={{ minWidth: 120 }}>
                  <InputLabel>条件</InputLabel>
                  <Select value={filterOp} label="条件"
                    onChange={(e) => setFilterOp(e.target.value as BiFilter['op'])}>
                    {['eq', 'neq', 'contains', 'gt', 'lt', 'empty', 'notEmpty'].map((op) => (
                      <MenuItem key={op} value={op}>{op}</MenuItem>
                    ))}
                  </Select>
                </FormControl>
                <TextField size="small" label="值" value={filterValue}
                  onChange={(e) => setFilterValue(e.target.value)}
                  disabled={filterOp === 'empty' || filterOp === 'notEmpty'} />
                <Button variant="contained" onClick={run} disabled={!canQuery}>
                  查询
                </Button>
              </Stack>
            </Grid>
          </Grid>
        </CardContent>
      </Card>

      {!canQuery && (
        <Alert severity="info">
          请选择数据集、至少一个维度（行/列）以及度量字段后查询。
        </Alert>
      )}

      {pivotQ.isLoading && (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
          <CircularProgress />
        </Box>
      )}

      {pivotQ.isError && (
        <Alert severity="error" sx={{ mb: 2 }}>
          透视查询失败：{(pivotQ.error as Error)?.message}
        </Alert>
      )}

      {pivotQ.data && (
        <Card sx={{ mb: 3 }}>
          <CardContent>
            <Stack direction="row" spacing={1} sx={{ mb: 2, alignItems: 'center' }}>
              <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
                透视结果
              </Typography>
              <Chip size="small" label={`${pivotQ.data.totalRows} 行 × ${pivotQ.data.totalCols} 列`} />
            </Stack>
            <Box sx={{ overflowX: 'auto' }}>
              <table style={{ borderCollapse: 'collapse', width: '100%', fontSize: 14 }}>
                <thead>
                  <tr>
                    <th style={{ border: '1px solid #e0e0e0', padding: 8 }}>维度</th>
                    {pivotQ.data.columns.map((c) => (
                      <th key={c} style={{ border: '1px solid #e0e0e0', padding: 8 }}>
                        {c || '（空）'}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {pivotQ.data.data.map((row, i) => (
                    <tr key={i}>
                      <td style={{ border: '1px solid #e0e0e0', padding: 8, fontWeight: 500 }}>
                        {String(row.row ?? '') || '（空）'}
                      </td>
                      {pivotQ.data!.columns.map((c) => (
                        <td key={c} style={{ border: '1px solid #e0e0e0', padding: 8 }}>
                          {String(row[`${c}|${measureField}`] ?? 0)}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </Box>
          </CardContent>
        </Card>
      )}

      {chartQ.data && chartData.length > 0 && (
        <Card>
          <CardContent>
            <Stack direction="row" spacing={2} sx={{ mb: 2, alignItems: 'center' }}>
              <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>图表</Typography>
              <FormControl size="small" sx={{ minWidth: 120 }}>
                <InputLabel>类型</InputLabel>
                <Select value={chartType} label="类型"
                  onChange={(e) => setChartType(e.target.value as 'bar' | 'line')}>
                  {CHART_TYPES.map((t) => (
                    <MenuItem key={t} value={t}>{t === 'bar' ? '柱状图' : '折线图'}</MenuItem>
                  ))}
                </Select>
              </FormControl>
            </Stack>
            <ResponsiveContainer width="100%" height={320}>
              {chartType === 'bar' ? (
                <BarChart data={chartData}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="name" />
                  <YAxis />
                  <Tooltip />
                  <Legend />
                  {chartQ.data.series.map((s, i) => (
                    <Bar key={s.name} dataKey={s.name}
                      fill={['#1976D2', '#FFC107', '#4CAF50', '#FF9800'][i % 4]} />
                  ))}
                </BarChart>
              ) : (
                <LineChart data={chartData}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="name" />
                  <YAxis />
                  <Tooltip />
                  <Legend />
                  {chartQ.data.series.map((s, i) => (
                    <Line key={s.name} type="monotone" dataKey={s.name}
                      stroke={['#1976D2', '#FFC107', '#4CAF50', '#FF9800'][i % 4]} />
                  ))}
                </LineChart>
              )}
            </ResponsiveContainer>
          </CardContent>
        </Card>
      )}
    </Box>
  );
}
