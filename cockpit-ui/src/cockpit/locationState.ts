import {
  activeViews,
  defaultLocationState,
  defaultLongRunningThreshold,
  longRunningStatusFilters,
  statusFilters,
  type ActiveView,
  type CockpitLocationState,
  type LongRunningStatusFilter,
  type StatusFilter,
} from './types';

const isActiveView = (value: string | null): value is ActiveView =>
  value !== null && activeViews.includes(value as ActiveView);

const isStatusFilter = (value: string | null): value is StatusFilter =>
  value !== null && statusFilters.includes(value as StatusFilter);

const isLongRunningStatusFilter = (value: string | null): value is LongRunningStatusFilter =>
  value !== null && longRunningStatusFilters.includes(value as LongRunningStatusFilter);

const normalizeFilterValue = (value: string | null) => {
  const trimmed = value?.trim();
  return trimmed ? trimmed : 'all';
};

const normalizeLongRunningThreshold = (value: string | null) => {
  const trimmed = value?.trim();
  return trimmed ? trimmed : defaultLongRunningThreshold;
};

export const readLocationState = (): CockpitLocationState => {
  const params = new URLSearchParams(window.location.search);
  const activeViewParam = params.get('tab');
  const statusFilterParam = params.get('status');
  const selectedInstanceFlowId = params.get('instanceFlowId')?.trim() || null;
  const selectedInstanceId = params.get('instanceId')?.trim() || null;

  return {
    activeView: isActiveView(activeViewParam) ? activeViewParam : defaultLocationState.activeView,
    searchTerm: params.get('q') ?? defaultLocationState.searchTerm,
    statusFilter: isStatusFilter(statusFilterParam) ? statusFilterParam : defaultLocationState.statusFilter,
    stageFilter: normalizeFilterValue(params.get('stage')),
    errorMessageFilter: params.get('error') ?? defaultLocationState.errorMessageFilter,
    showIncompleteOnly: ['1', 'true', 'yes'].includes((params.get('incomplete') ?? '').toLowerCase()),
    errorFlowFilter: normalizeFilterValue(params.get('errorFlow')),
    errorStageFilter: normalizeFilterValue(params.get('errorStage')),
    errorMessageFilterErrors: params.get('errorMessage') ?? defaultLocationState.errorMessageFilterErrors,
    longRunningFlowFilter: normalizeFilterValue(params.get('lrFlow')),
    longRunningStatusFilter: isLongRunningStatusFilter(params.get('lrStatus'))
      ? params.get('lrStatus') as LongRunningStatusFilter
      : defaultLocationState.longRunningStatusFilter,
    longRunningThreshold: normalizeLongRunningThreshold(params.get('lrThreshold')),
    selectedInstanceFlowId: selectedInstanceFlowId && selectedInstanceId ? selectedInstanceFlowId : null,
    selectedInstanceId: selectedInstanceFlowId && selectedInstanceId ? selectedInstanceId : null,
  };
};

export const buildLocationSearch = (state: CockpitLocationState) => {
  const params = new URLSearchParams();
  params.set('tab', state.activeView);

  if (state.searchTerm) params.set('q', state.searchTerm);
  if (state.statusFilter !== 'all') params.set('status', state.statusFilter);
  if (state.stageFilter !== 'all') params.set('stage', state.stageFilter);
  if (state.errorMessageFilter) params.set('error', state.errorMessageFilter);
  if (state.showIncompleteOnly) params.set('incomplete', '1');
  if (state.errorFlowFilter !== 'all') params.set('errorFlow', state.errorFlowFilter);
  if (state.errorStageFilter !== 'all') params.set('errorStage', state.errorStageFilter);
  if (state.errorMessageFilterErrors) params.set('errorMessage', state.errorMessageFilterErrors);
  if (state.longRunningFlowFilter !== 'all') params.set('lrFlow', state.longRunningFlowFilter);
  if (state.longRunningStatusFilter !== 'default') params.set('lrStatus', state.longRunningStatusFilter);
  if (state.longRunningThreshold.trim() && state.longRunningThreshold.trim() !== defaultLongRunningThreshold) {
    params.set('lrThreshold', state.longRunningThreshold.trim());
  }
  if (state.selectedInstanceFlowId && state.selectedInstanceId) params.set('instanceFlowId', state.selectedInstanceFlowId);
  if (state.selectedInstanceFlowId && state.selectedInstanceId) params.set('instanceId', state.selectedInstanceId);

  const search = params.toString();
  return search ? `?${search}` : '';
};