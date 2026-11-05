import type { Status, DiscardReason } from './states';

export type RiskLevel = 'low' | 'mid' | 'high' | 'forbidden';
export type AutonomyLevel = 'L1' | 'L2' | 'L3';
export type KillMode = 'READ_ONLY' | 'FREEZE_RELEASE' | 'HALT';
export type CatalogState = 'ACTIVE' | 'PAUSED' | 'ARCHIVED';
export type ExecutionState = 'WAITING' | 'RUNNING' | 'RETRYING' | 'FAILED' | 'BLOCKED' | 'PAUSED' | 'COMPLETED' | 'DISCARDED' | 'WAITING_CONFIGURATION' | 'WAITING_INPUT' | 'WAITING_PLAN' | 'WAITING_APPROVAL' | 'WAITING_EXTERNAL' | 'WAITING_VALIDATION' | 'RECOVERY_REQUIRED' | 'CANCELLED' | 'SUCCEEDED';
export interface BudgetLimits { dailyUsd: number | null; dailyTokens: number | null; dailyCiMinutes: number | null; /** 本项目新任务默认：不限预算 / 预算用完改用省钱模式；null = 跟随治理默认 */ taskUnlimited?: boolean | null; taskDegrade?: boolean | null }
/** 任务预算开关的生效值；from = task（任务单独设置）| project（项目默认）| governance（治理默认） */
export interface BudgetSwitches { budgetOff: boolean; budgetFrom: 'task' | 'project' | 'governance'; degradeOff: boolean; degradeFrom: 'task' | 'project' | 'governance'; safetyMaxIterations: number }
export interface ProjectModule { name: string; path: string; language: string; testCommand: string; buildCommand: string }
export interface ProjectConfig {
  /** 可以为 null：建项目时暂不绑定仓库（#18） */
  repo: string | null; baseBranch: string; workflow: string; autonomy: AutonomyLevel; credentialRef: string; technology: string;
  intake: boolean; intakeLabel: string; intakeAll: boolean; releasePrefix: string; maxConcurrentTasks: number;
  releaseTarget?: 'github_release' | 'wechat_miniprogram';
  autoInitializeRepository?: boolean;
  /** 开发方式：sandbox（沙盒，默认）| legacy（快速方式） */
  devMode?: DevMode;
  /** 逐行审查最多审几组（1–30）；null / 不填 = 平台默认 12 */
  codeReviewMaxGroups?: number | null;
  allowedEnvNames?: string[]; serverEnvPaths?: string[];
  budget: BudgetLimits; modules: ProjectModule[]; allowedPaths: string[]; allowedExtensions: string[];
  pathRules: { prefix: string; level: RiskLevel }[];
  discovery: { enabled: boolean; source: boolean; testFailures: boolean; autoCreateTasks: boolean; intervalMinutes: number; maxFiles: number; roots: string[] };
}
export interface ReleaseCapability { target: string; ready: boolean; code: string | null; detail: string }
export interface DeliveryStatus { target: string; githubRelease: 'NOT_CREATED' | 'CREATED' | 'VERIFIED'; deployment: 'NOT_REQUESTED' | 'WAITING_CONFIGURATION'; detail: string }
export interface ProjectDTO {
  releaseCapability?: ReleaseCapability;
  id: string; productLineId: string; productLineName?: string; name: string; description: string; owner: string;
  state: CatalogState; paused: boolean; autonomy: AutonomyLevel; effectiveAutonomy: AutonomyLevel;
  config: ProjectConfig; configVersion: number; createdAt: string; updatedAt: string;
  taskCount?: number; activeTasks?: number; spentUsd?: number; lastScan?: { state: string; summary: string; started_at: string } | null;
}

export interface RecoveryInfo {
  canResume: boolean; code?: string | null; state: string;
  rework?: { node: string; limit: number; qaLimit: number; used: number; total: number; round: number };
  checkpoint: { phase?: string | null; stage?: string | null; node?: string | null; segmentIndex?: number; iteration?: number; pr?: { number: number; branch: string; headSha: string } | null };
}
export type DevMode = 'sandbox' | 'legacy';
/** 本任务实际生效的开发方式：override = 任务覆盖（null 表示跟随项目），source = task | project | default */
export interface TaskDevMode { mode: DevMode; source: 'task' | 'project' | 'default'; override: DevMode | null }
export interface TaskDTO {
  devMode?: TaskDevMode;
  delivery?: DeliveryStatus;
  version?: number; recovery?: RecoveryInfo;
  budgetSwitches?: BudgetSwitches;
  projectId: string; projectName: string; productLineName: string; currentStage: Status; executionState: ExecutionState;
  acceptanceCriteria: string; pipelineVersion: string; projectConfigVersion: number;
  id: string; productLine: string; title: string; problem: string;
  priorityScore: number; riskLevel: RiskLevel; status: Status;
  path: string[]; scopeLevel: number; strategyVariant: number; currentIteration: number;
  loopCount: number; rejectCount: number; reviewRetry: number; rollbackCount: number; requeueCount: number;
  degradeCount: number; healCount: number;
  discardReason: DiscardReason | null; cooldownUntil: string | null;
  parentTaskId: string | null; paused: boolean;
  budgetTokens: number; spentTokens: number; budgetUsd: number; spentUsd: number;
  createdAt: string; statusChangedAt: string; releasedAt: string | null; nextRunAt: string | null;
}

export interface BoardNode { id: string; label: string; resident: number; flow: number }
export interface BoardResponse {
  projectId?: string | null;
  range: { from: string; to: string }; productLine: string | null; now: string;
  nodes: BoardNode[];
  totals: { all: number; watch: number; prism: number; delivered: number; released: number; discarded: number };
  edges: Record<string, number>;
  loops: number; iterations: number; closureRate: number;
  evidence: { count: number; productionDeliveries: number; repositoryReleases?: number; recheckRate: number };
  trend: { date: string; created: number; delivered: number }[];
  health: Record<string, number>;
  discardReasons: Record<string, number>;
  /** 按业务线筛选时：该业务线的流程模板与被关闭（跳过）的节点 */
  pipeline?: { template: string; snapshot: string; skipped: string[] };
}
