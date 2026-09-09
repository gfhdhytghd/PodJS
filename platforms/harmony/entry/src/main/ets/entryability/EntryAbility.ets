import { AbilityConstant, UIAbility, Want } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { NotificationOpenReceiver } from '../NotificationOpenReceiver';
import { reminderJournal } from '../NativeReminderStore';
import { notificationAbilityVisible } from '../NativeNotificationDelivery';
import { syncStateAbilityVisible } from '../NativeSyncStateDelivery';
import { syncMessageAbilityVisible } from '../NativeSyncMessageDelivery';
import { syncFileAbilityVisible } from '../NativeSyncFileDelivery';
import { installedSyncAbilityVisible } from '../NativeInstalledSyncConnection';
import { BackgroundRecovery } from '../BackgroundRecovery';
import { nativeBackgroundMaintenance } from '../NativeBackgroundSchedules';
export default class EntryAbility extends UIAbility {
  private opens: NotificationOpenReceiver = new NotificationOpenReceiver(() => reminderJournal(this.context));
  private recovery: BackgroundRecovery = new BackgroundRecovery(() => nativeBackgroundMaintenance(this.context));
  private recoveryTimer: number = -1;
  private recoverBackground(): void {
    this.recovery.pump().catch(() => { console.error('PodJS background recovery deferred'); });
  }
  private backgroundMaintenance(active: boolean): void {
    this.recovery.setActive(active);
    if (active && this.recoveryTimer === -1) {
      this.recoverBackground(); this.recoveryTimer = setInterval(() => this.recoverBackground(), 5000);
    } else if (!active && this.recoveryTimer !== -1) { clearInterval(this.recoveryTimer); this.recoveryTimer = -1; }
  }
  private capture(want: Want): void {
    this.opens.capture(want.parameters?.['podjsNotificationToken'], want.parameters?.['podjsNotificationId'])
      .catch(() => { console.error('PodJS notification open could not be persisted; retained for foreground retry'); });
  }
  onCreate(want: Want, _params: AbilityConstant.LaunchParam): void { this.capture(want); }
  onNewWant(want: Want, _params: AbilityConstant.LaunchParam): void { this.capture(want); }
  onForeground(): void { notificationAbilityVisible(true); syncStateAbilityVisible(true); syncMessageAbilityVisible(true); syncFileAbilityVisible(true); installedSyncAbilityVisible(true); this.opens.retry(); this.backgroundMaintenance(true); }
  onBackground(): void { installedSyncAbilityVisible(false); notificationAbilityVisible(false); syncStateAbilityVisible(false); syncMessageAbilityVisible(false); syncFileAbilityVisible(false); this.backgroundMaintenance(false); }
  onDestroy(): void { installedSyncAbilityVisible(false); notificationAbilityVisible(false); syncStateAbilityVisible(false); syncMessageAbilityVisible(false); syncFileAbilityVisible(false); this.backgroundMaintenance(false); }
  onWindowStageCreate(stage: window.WindowStage): void { stage.loadContent('pages/Index'); }
}
