export type ConfirmationActionKind = 'retry' | 'cancel' | 'change-stage';

export interface ConfirmationActionState {
  kind: ConfirmationActionKind;
  instanceIds: string[];
  targetStage?: string;
}

export type CockpitStatus = 'Running' | 'WaitingForTimer' | 'WaitingForEvent' | 'PendingEngine' | 'Error' | 'Completed' | 'Cancelled';
export type HistoryEventType = 'Started' | 'EventAppended' | 'StatusChanged' | 'StageChanged' | 'Retried' | 'ManualStageChanged' | 'Cancelled' | 'Error';
export type ActiveView = 'flows' | 'errors' | 'long-running' | 'instances';
export type StatusFilter = 'all' | CockpitStatus;
export type LongRunningStatusFilter = 'default' | 'all' | Extract<CockpitStatus, 'Running' | 'WaitingForTimer' | 'WaitingForEvent' | 'PendingEngine'>;

export interface FlowDto {
  flowId: string;
  diagram: string;
  stages: string[];
  notCompletedCount: number;
  errorCount: number;
  activeCount: number;
  completedCount: number;
  longRunningCount: number;
  stageBreakdown: Array<{
    stage: string;
    totalCount: number;
    errorCount: number;
  }>;
}

export interface InstanceDto {
  flowId: string;
  flowInstanceId: string;
  stage: string | null;
  cockpitStatus: CockpitStatus;
  lastUpdatedAt: string;
  lastErrorMessage: string | null;
}

export interface ErrorGroupDto {
  flowId: string;
  stage: string | null;
  count: number;
}

export interface HistoryEntryDto {
  occurredAt: string;
  type: HistoryEventType;
  stage?: string | null;
  fromStage?: string | null;
  toStage?: string | null;
  fromStatus?: string | null;
  toStatus?: string | null;
  event?: string | null;
  errorType?: string | null;
  errorMessage?: string | null;
  errorStackTrace?: string | null;
}

export interface UiInstance {
  id: string;
  flowId: string;
  stage: string;
  cockpitStatus: CockpitStatus;
  updatedAt: Date;
  createdAt: Date;
  errorMessage: string | null;
}

export interface CockpitLocationState {
  activeView: ActiveView;
  searchTerm: string;
  statusFilter: StatusFilter;
  stageFilter: string;
  errorMessageFilter: string;
  showIncompleteOnly: boolean;
  errorFlowFilter: string;
  errorStageFilter: string;
  errorMessageFilterErrors: string;
  longRunningFlowFilter: string;
  longRunningStatusFilter: LongRunningStatusFilter;
  longRunningThreshold: string;
  selectedInstanceFlowId: string | null;
  selectedInstanceId: string | null;
}

export const activeViews: ActiveView[] = ['flows', 'errors', 'long-running', 'instances'];
export const statusFilters: StatusFilter[] = ['all', 'Running', 'WaitingForTimer', 'WaitingForEvent', 'PendingEngine', 'Error', 'Completed', 'Cancelled'];
export const longRunningStatusFilters: LongRunningStatusFilter[] = ['default', 'all', 'Running', 'PendingEngine', 'WaitingForTimer', 'WaitingForEvent'];
export const defaultLongRunningThreshold = '1h';
export const defaultLongRunningThresholdSeconds = 60 * 60;

export const defaultLocationState: CockpitLocationState = {
  activeView: 'flows',
  searchTerm: '',
  statusFilter: 'all',
  stageFilter: 'all',
  errorMessageFilter: '',
  showIncompleteOnly: false,
  errorFlowFilter: 'all',
  errorStageFilter: 'all',
  errorMessageFilterErrors: '',
  longRunningFlowFilter: 'all',
  longRunningStatusFilter: 'default',
  longRunningThreshold: defaultLongRunningThreshold,
  selectedInstanceFlowId: null,
  selectedInstanceId: null,
};

export const toUiInstance = (instance: InstanceDto): UiInstance => ({
  id: instance.flowInstanceId,
  flowId: instance.flowId,
  stage: instance.stage ?? '',
  cockpitStatus: instance.cockpitStatus,
  updatedAt: new Date(instance.lastUpdatedAt),
  createdAt: new Date(instance.lastUpdatedAt),
  errorMessage: instance.lastErrorMessage,
});