export type ConfirmationActionKind = 'retry' | 'cancel' | 'change-stage';

export interface ConfirmationActionState {
  kind: ConfirmationActionKind;
  instanceIds: string[];
  targetStage?: string;
}

export type InstanceStatus = 'Pending' | 'Running' | 'Completed' | 'Error' | 'Cancelled';
export type ActivityStatus = 'Running' | 'Pending' | 'WaitingForTimer' | 'WaitingForEvent';
export type HistoryEventType = 'Started' | 'EventAppended' | 'StatusChanged' | 'StageChanged' | 'Retried' | 'ManualStageChanged' | 'Cancelled' | 'Error';
export type ActiveView = 'flows' | 'errors' | 'long-running' | 'instances';
export type StatusFilter = 'all' | InstanceStatus;
export type LongRunningActivityFilter = 'default' | 'all' | ActivityStatus;

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
  status: InstanceStatus | null;
  activityStatus: ActivityStatus | null;
  lastUpdatedAt: string;
  lastErrorMessage: string | null;
}

export interface ErrorGroupDto {
  flowId: string;
  stage: string | null;
  count: number;
  instanceIds: string[];
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
  status: InstanceStatus;
  activityStatus: ActivityStatus | null;
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
  longRunningActivityFilter: LongRunningActivityFilter;
  longRunningThreshold: string;
  selectedInstanceFlowId: string | null;
  selectedInstanceId: string | null;
}

export const activeViews: ActiveView[] = ['flows', 'errors', 'long-running', 'instances'];
export const statusFilters: StatusFilter[] = ['all', 'Pending', 'Running', 'Completed', 'Error', 'Cancelled'];
export const activityStatuses: ActivityStatus[] = ['Running', 'Pending', 'WaitingForTimer', 'WaitingForEvent'];
export const longRunningActivityFilters: LongRunningActivityFilter[] = ['default', 'all', ...activityStatuses];
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
  longRunningActivityFilter: 'default',
  longRunningThreshold: defaultLongRunningThreshold,
  selectedInstanceFlowId: null,
  selectedInstanceId: null,
};

export const toUiInstance = (instance: InstanceDto): UiInstance => ({
  id: instance.flowInstanceId,
  flowId: instance.flowId,
  stage: instance.stage ?? '',
  status: instance.status as InstanceStatus,
  activityStatus: instance.activityStatus,
  updatedAt: new Date(instance.lastUpdatedAt),
  createdAt: new Date(instance.lastUpdatedAt),
  errorMessage: instance.lastErrorMessage,
});