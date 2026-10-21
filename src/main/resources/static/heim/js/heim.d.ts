// Heim UI — window.Heim. Plain JS: every factory returns an HTMLElement (or DocumentFragment)
// built from one template string. Types document the data the REST API should return.

export type Status = 'up' | 'degraded' | 'down' | 'unknown' | 'off';
export type LinkKind = 'Public' | 'Domain' | 'Hostname' | 'IP' | 'VPN' | 'Other';

export interface Probe { status: Exclude<Status, 'degraded'>; http?: number; latency?: number; error?: string; }
export interface HealthCheck { enabled?: boolean; probeUrl?: string; codes?: string; ignoreTls?: boolean; followRedirects?: boolean; interval?: number; timeout?: number; }
export interface Link { id: string; label: string; kind: LinkKind; url: string; primary?: boolean; newTab?: boolean; health?: HealthCheck; probe?: Probe; }
export interface Service { id: string; name: string; description?: string; icon?: { src?: string; glyph?: string } | null; tabs: string[]; links: Link[]; }
export interface Tab { id: string; name: string; isDefault?: boolean; showAll?: boolean; color?: string; glyph?: string; }

export interface StatusDotProps { status: Status; size?: 'sm'; label?: string; decorative?: boolean; }
export interface LinkChipProps { link: Link; tooltipBelow?: boolean; }
export interface ServiceIconProps { icon?: Service['icon']; name: string; size?: 'lg'; }
export interface ServiceCardProps { service: Service; maxLinks?: number; }
export interface ServiceGridProps { services: Service[]; editing?: boolean; maxLinks?: number; }
export interface TabBarProps { tabs: Tab[]; active: string; editing?: boolean; }
export interface TopBarProps { title?: string; tabs?: Tab[]; active?: string; editing?: boolean; user?: string | null; canEdit?: boolean; settingsHref?: string; /** false hides the Log in button */ loginHref?: string | false; }
export interface EmptyStateProps { title: string; body?: string; icon?: string; firstRun?: boolean; actions?: { label: string; primary?: boolean; icon?: string; action?: string }[]; }
export interface ButtonProps { label: string; variant?: 'primary' | 'ghost' | 'danger' | 'danger-ghost'; size?: 'sm'; icon?: string; disabled?: boolean; action?: string; type?: string; }
export interface FieldProps { label: string; id?: string; type?: string | 'select' | 'textarea'; value?: string; placeholder?: string; options?: string[]; mono?: boolean; code?: boolean; suffix?: string; icon?: string; hint?: string; error?: string; optional?: boolean; }
export interface SwitchProps { label: string; hint?: string; checked?: boolean; disabled?: boolean; id?: string; }
export interface SegmentedControlProps { label: string; value: string; block?: boolean; options: { value: string; label: string; icon?: string }[]; }
export interface ChoiceChipsProps { label: string; selected?: string[]; options: { value: string; label: string }[]; }
export interface IconPickerProps { service: Service; source?: 'favicon' | 'library' | 'url' | 'upload'; query?: string; }
export interface LinkRowProps { link: Link; index: number; open?: boolean; defaults?: { interval: number; timeout: number; codes: string }; }
export interface DialogProps { kind: 'drawer' | 'modal'; title: string; body: (Node | string)[]; footer?: (Node | string)[]; }
export interface CalloutProps { tone?: 'warning' | 'danger'; title?: string; body: string; icon?: string; }

export interface HeimNamespace {
  StatusDot(p: StatusDotProps): HTMLElement;
  LinkChip(p: LinkChipProps): HTMLElement;
  LinkOverflow(p: { links: Link[]; serviceName: string }): HTMLElement;
  ServiceIcon(p: ServiceIconProps): HTMLElement;
  ServiceCard(p: ServiceCardProps): HTMLElement;
  AddServiceCard(): HTMLElement;
  ServiceGrid(p: ServiceGridProps): HTMLElement;
  TabBar(p: TabBarProps): HTMLElement;
  TopBar(p: TopBarProps): DocumentFragment;
  EmptyState(p: EmptyStateProps): HTMLElement;
  Button(p: ButtonProps): HTMLElement;
  Field(p: FieldProps): HTMLElement;
  Switch(p: SwitchProps): HTMLElement;
  SegmentedControl(p: SegmentedControlProps): HTMLElement;
  ChoiceChips(p: ChoiceChipsProps): HTMLElement;
  IconPicker(p: IconPickerProps): HTMLElement;
  LinkRow(p: LinkRowProps): HTMLElement;
  Dialog(p: DialogProps): HTMLElement;
  Callout(p: CalloutProps): HTMLElement;
  /** Composite: the whole main view, re-rendered on tab switch and edit toggle. */
  Dashboard(root: HTMLElement, cfg: { title: string; tabs: Tab[]; services: Service[]; user?: string; tab?: string; editing?: boolean; background?: string; firstRun?: boolean; canEdit?: boolean; settingsHref?: string; loginHref?: string | false; servicesFor?: (tabId: string) => Service[]; demo?: boolean; onAction?: (name: string, detail: object, e?: Event) => void }): { state: { tab: string; editing: boolean }; render(): void };
  /** Composite: the service editor drawer. */
  ServiceEditor(p: { service: Service; tabs: Tab[]; isNew?: boolean; iconSource?: string; openHealth?: number; defaults?: LinkRowProps['defaults'] }): HTMLElement;
  computeStatus(links: Link[]): { status: Status; down: number; total: number };
  setStatus(dot: Element, status: Status, label?: string): void;
  /** Push one live probe result; updates chip, tooltip, overflow row and the card's overall status. */
  applyProbe(root: ParentNode, r: { serviceId: string; linkId: string; kind?: string } & Probe): void;
  mount(root: HTMLElement, opts?: { demo?: boolean; onAction?: (name: string, detail: object, e?: Event) => void }): HTMLElement;
  icon(name: string, cls?: string): string;
  renderIcons(root?: ParentNode): void;
  theme: { init(pref?: 'system' | 'light' | 'dark'): 'light' | 'dark'; set(pref: 'system' | 'light' | 'dark'): 'light' | 'dark' };
}
declare global { interface Window { Heim: HeimNamespace } }
