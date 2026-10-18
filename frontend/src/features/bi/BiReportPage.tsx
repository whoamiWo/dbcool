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
import { useTranslation } from 'react-i18next';
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
  const { t } = useTranslation();
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
        {t('biReport.title')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        {t('biReport.subtitle')}
      </Typography>

      <Card sx={{ mb: 3 }}>
        <CardContent>
          <Grid container spacing={2}>
            <Grid size={{ xs: 12, md: 3 }}>
              <FormControl fullWidth size="small">
                <InputLabel>{t('biReport.dataset')}</InputLabel>
                <Select
                  value={collection}
                  label={t('biReport.dataset')}
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
                <InputLabel>{t('biReport.rowDimension')}</InputLabel>
                <Select
                  value={rows[0] ?? ''}
                  label={t('biReport.rowDimension')}
                  onChange={(e) => setRows(e.target.value ? [e.target.value] : [])}
                >
                  <MenuItem value="">{t('biReport.none')}</MenuItem>
                  {fields.map((f) => (
                    <MenuItem key={f.name} value={f.name}>{f.name}</MenuItem>
                  ))}
                </Select>
              </FormControl>
            </Grid>

            <Grid size={{ xs: 12, md: 3 }}>
              <FormControl fullWidth size="small">
                <InputLabel>{t('biReport.colDimension')}</InputLabel>
                <Select
                  value={columns[0] ?? ''}
                  label={t('biReport.colDimension')}
                  onChange={(e) => setColumns(e.target.value ? [e.target.value] : [])}
                >
                  <MenuItem value="">{t('biReport.none')}</MenuItem>
                  {fields.map((f) => (
                    <MenuItem key={f.name} value={f.name}>{f.name}</MenuItem>
                  ))}
                </Select>
              </FormControl>
            </Grid>

            <Grid size={{ xs: 6, md: 1.5 }}>
              <FormControl fullWidth size="small">
                <InputLabel>{t('biReport.measure')}</InputLabel>
                <Select
                  value={measureField}
                  label={t('biReport.measure')}
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
                <InputLabel>{t('biReport.aggregation')}</InputLabel>
                <Select value={agg} label={t('biReport.aggregation')}
                  onChange={(e) => setAgg(e.target.value as BiMeasure['agg'])}>
                  {AGGS.map((a) => <MenuItem key={a} value={a}>{a}</MenuItem>)}
                </Select>
              </FormControl>
            </Grid>

            <Grid size={{ xs: 12 }}>
              <Divider sx={{ my: 1 }} />
              <Stack direction="row" spacing={2}
                sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
                <Typography variant="body2" color="text.secondary">{t('biReport.filterLabel')}</Typography>
                <FormControl size="small" sx={{ minWidth: 140 }}>
                  <InputLabel>{t('biReport.field')}</InputLabel>
                  <Select value={filterField} label={t('biReport.field')}
                    onChange={(e) => setFilterField(e.target.value)}>
                    <MenuItem value="">{t('biReport.none')}</MenuItem>
                    {fields.map((f) => (
                      <MenuItem key={f.name} value={f.name}>{f.name}</MenuItem>
                    ))}
                  </Select>
                </FormControl>
                <FormControl size="small" sx={{ minWidth: 120 }}>
                  <InputLabel>{t('biReport.condition')}</InputLabel>
                  <Select value={filterOp} label={t('biReport.condition')}
                    onChange={(e) => setFilterOp(e.target.value as BiFilter['op'])}>
                    {['eq', 'neq', 'contains', 'gt', 'lt', 'empty', 'notEmpty'].map((op) => (
                      <MenuItem key={op} value={op}>{op}</MenuItem>
                    ))}
                  </Select>
                </FormControl>
                <TextField size="small" label={t('biReport.value')} value={filterValue}
                  onChange={(e) => setFilterValue(e.target.value)}
                  disabled={filterOp === 'empty' || filterOp === 'notEmpty'} />
                <Button variant="contained" onClick={run} disabled={!canQuery}>
                  {t('biReport.query')}
                </Button>
              </Stack>
            </Grid>
          </Grid>
        </CardContent>
      </Card>

      {!canQuery && (
        <Alert severity="info">
          {t('biReport.selectHint')}
        </Alert>
      )}

      {pivotQ.isLoading && (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
          <CircularProgress />
        </Box>
      )}

      {pivotQ.isError && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {t('biReport.pivotQueryFailed')}: {(pivotQ.error as Error)?.message}
        </Alert>
      )}

      {pivotQ.data && (
        <Card sx={{ mb: 3 }}>
          <CardContent>
            <Stack direction="row" spacing={1} sx={{ mb: 2, alignItems: 'center' }}>
              <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
                {t('biReport.pivotResult')}
              </Typography>
              <Chip size="small" label={t('biReport.pivotSize', { rows: pivotQ.data.totalRows, cols: pivotQ.data.totalCols })} />
            </Stack>
            <Box sx={{ overflowX: 'auto' }}>
              <table style={{ borderCollapse: 'collapse', width: '100%', fontSize: 14 }}>
                <thead>
                  <tr>
                    <th style={{ border: '1px solid var(--color-border-light)', padding: 8 }}>{t('biReport.dimension')}</th>
                    {pivotQ.data.columns.map((c) => (
                      <th key={c} style={{ border: '1px solid var(--color-border-light)', padding: 8 }}>
                        {c || t('biReport.empty')}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {pivotQ.data.data.map((row, i) => (
                    <tr key={i}>
                      <td style={{ border: '1px solid var(--color-border-light)', padding: 8, fontWeight: 500 }}>
                        {String(row.row ?? '') || t('biReport.empty')}
                      </td>
                      {pivotQ.data!.columns.map((c) => (
                        <td key={c} style={{ border: '1px solid var(--color-border-light)', padding: 8 }}>
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
              <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>{t('biReport.chart')}</Typography>
              <FormControl size="small" sx={{ minWidth: 120 }}>
                <InputLabel>{t('biReport.type')}</InputLabel>
                <Select value={chartType} label={t('biReport.type')}
                  onChange={(e) => setChartType(e.target.value as 'bar' | 'line')}>
                  {CHART_TYPES.map((ct) => (
                    <MenuItem key={ct} value={ct}>{ct === 'bar' ? t('biReport.barChart') : t('biReport.lineChart')}</MenuItem>
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
                      fill={['var(--color-info)', 'var(--color-warning)', 'var(--color-success)', 'var(--color-warning)'][i % 4]} />
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
                      stroke={['var(--color-info)', 'var(--color-warning)', 'var(--color-success)', 'var(--color-warning)'][i % 4]} />
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
