import * as podjs from 'libpodjs_harmony.so';
import { InstalledSyncPanel } from '../InstalledSyncPanel';
import { InstalledPairingPanel } from '../InstalledPairingPanel';
import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import { ServicePump, ServiceTransport, UnsupportedServices } from '../ServicePump';
import { AuthorizedServices, ServiceAuthority } from '../AuthorizedServices';
import { NotificationPermissions } from '../NotificationPermissions';
import { NativeNotificationPermission } from '../NativeNotificationPermission';
import { NotificationServices } from '../NotificationSchedules';
import { notificationSchedules } from '../NativeReminderStore';
import { BackgroundServices } from '../BackgroundServices';
import { nativeBackgroundSchedules } from '../NativeBackgroundSchedules';
import { SyncStateServices } from '../SyncStateServices';
import { nativeInstalledSyncState, nativeInstalledSyncOutbox, nativeInstalledSyncInbox, nativeInstalledSyncIncoming, verifyInstalledIncomingIdentity } from '../NativeInstalledSync';
import { SyncIncomingFileServices } from '../SyncIncomingFileServices';
import { SyncOutgoingFileServices } from '../SyncOutgoingFileServices';
import { installedSyncPageVisible, synchronizeInstalledState, acknowledgeInstalledMessage } from '../NativeInstalledSyncConnection';
import { nativeInstalledSyncFileClient, newInstalledFileTransferId } from '../NativeInstalledSync';
import { attachSyncFileDelivery, detachSyncFileDelivery, syncFilePageVisible,
  refreshSyncFileDelivery } from '../NativeSyncFileDelivery';
import { SyncMessageServices } from '../SyncMessageServices';
import { SyncMessageInbox } from '../SyncMessageInbox';
import { attachSyncMessageDelivery, detachSyncMessageDelivery, syncMessagePageVisible,
  refreshSyncMessageDelivery } from '../NativeSyncMessageDelivery';
import { attachSyncStateDelivery, detachSyncStateDelivery, syncStatePageVisible,
  refreshSyncStateDelivery } from '../NativeSyncStateDelivery';
import { attachNotificationDelivery, detachNotificationDelivery, notificationPageVisible,
  refreshNotificationDelivery, notificationEffect } from '../NativeNotificationDelivery';

class NativeAuthority implements ServiceAuthority {
  hasCapability(name: string): boolean { return podjs.hasCapability(name); }
}

class NativeServices implements ServiceTransport {
  private encoder: util.TextEncoder = new util.TextEncoder();
  poll(): string | null { return podjs.pollEffect(); }
  post(json: string): boolean { return podjs.postEvent(this.encoder.encodeInto(json)); }
}

@Entry @Component struct Index {
  @State private syncSettings: boolean = false;
  @State private syncInvitation: boolean = false;
  @State private syncAvailable: boolean = false;
  private xcomponentController: XComponentController = new XComponentController();
  private services: ServicePump = new ServicePump(new NativeServices(),
    new AuthorizedServices(new NativeAuthority(), new UnsupportedServices()));
  aboutToAppear(): void {
    const facts = podjs.preflight('harmonyos-watch', 2);
    if (!facts.ok) throw new Error(`PodJS preflight: ${facts.error}`);
    const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
    attachNotificationDelivery(context);
    attachSyncStateDelivery(context);
    const transport = new NativeServices();
    const inbox = new SyncMessageInbox(() => nativeInstalledSyncInbox(context), () => Date.now(),
      (json: string) => transport.post(json), acknowledgeInstalledMessage);
    attachSyncMessageDelivery(inbox);
    const notifications = new NotificationServices(new NotificationPermissions(new NativeNotificationPermission(context)), () => notificationSchedules(context));
    const background = new BackgroundServices(notifications, () => nativeBackgroundSchedules(context));
    const state = new SyncStateServices(background, () => nativeInstalledSyncState(context), synchronizeInstalledState);
    const messages = new SyncMessageServices(state, () => nativeInstalledSyncOutbox(context), () => Date.now(), inbox);
    const files = new SyncIncomingFileServices(messages, () => nativeInstalledSyncIncoming(context),
      (id: string, peer: string) => verifyInstalledIncomingIdentity(context, id, peer));
    const outgoing = new SyncOutgoingFileServices(files, () => nativeInstalledSyncFileClient(context), newInstalledFileTransferId);
    attachSyncFileDelivery(files, outgoing);
    this.services = new ServicePump(transport, new AuthorizedServices(new NativeAuthority(), outgoing), notificationEffect);
    podjs.setEffectListener(() => this.services.pump());
  }
  aboutToDisappear(): void {
    installedSyncPageVisible(false);
    notificationPageVisible(false);
    detachNotificationDelivery();
    detachSyncStateDelivery();
    syncMessagePageVisible(false);
    detachSyncMessageDelivery();
    syncFilePageVisible(false);
    detachSyncFileDelivery();
    podjs.setEffectListener(null);
    this.services.close();
  }
  onPageShow(): void { notificationPageVisible(true); syncStatePageVisible(true); syncMessagePageVisible(true); syncFilePageVisible(true); installedSyncPageVisible(true); }
  onPageHide(): void { this.syncSettings = false; installedSyncPageVisible(false); notificationPageVisible(false); syncStatePageVisible(false); syncMessagePageVisible(false); syncFilePageVisible(false); }
  onBackPress(): boolean {
    if (!this.syncSettings) return false;
    if (this.syncInvitation) this.syncInvitation = false; else this.closeSyncSettings(); return true;
  }
  private closeSyncSettings(): void {
    this.syncSettings = false;
    this.syncInvitation = false;
    this.getUIContext().getFocusController().requestFocus('podjs-crown-target');
  }
  private bootRuntime(): void {
    const context = getContext(this) as common.UIAbilityContext;
    podjs.accessibilityStateLabels([
      context.resourceManager.getStringSync($r('app.string.pod_a11y_checked').id),
      context.resourceManager.getStringSync($r('app.string.pod_a11y_unchecked').id),
      context.resourceManager.getStringSync($r('app.string.pod_a11y_mixed').id),
      context.resourceManager.getStringSync($r('app.string.pod_a11y_expanded').id),
      context.resourceManager.getStringSync($r('app.string.pod_a11y_collapsed').id),
      context.resourceManager.getStringSync($r('app.string.pod_a11y_busy').id)
    ]);
    const ok = podjs.boot(
      context.resourceManager.getRawFileContentSync('main.js'),
      context.resourceManager.getRawFileContentSync('main.pak'),
      context.resourceManager.getRawFileContentSync('pod.manifest.json'), context.filesDir);
    if (!ok) throw new Error('PodJS native runtime did not start');
    this.syncAvailable = podjs.hasCapability('companion.sync.state') || podjs.hasCapability('companion.sync.message') || podjs.hasCapability('companion.sync.file');
    refreshNotificationDelivery();
    refreshSyncStateDelivery();
    refreshSyncMessageDelivery();
    refreshSyncFileDelivery();
  }
  private handleCrown(event: CrownEvent): void {
    if (this.syncSettings) return;
    podjs.rotary(Math.round(event.degree * 1000));
    event.stopPropagation();
  }
  build() {
    Stack() {
      XComponent({ id: 'podjs', type: XComponentType.SURFACE, libraryname: 'podjs_harmony',
        controller: this.xcomponentController })
        .width('100%').height('100%')
        .focusable(false)
        .onLoad(() => this.bootRuntime())
      if (this.syncSettings) {
        if (this.syncInvitation) {
          InstalledPairingPanel({ onClose: () => { this.syncInvitation = false; } })
        } else {
          InstalledSyncPanel({ onClose: () => this.closeSyncSettings(), onPair: () => { this.syncInvitation = true; } })
        }
      } else if (this.syncAvailable) {
        Button('同步').fontSize(14).height(32).position({ x: '38%', y: '82%' })
          .accessibilityText('打开宿主同步设备设置').onClick(() => { this.syncSettings = true; })
      }
    }
      .id('podjs-crown-target')
      .width('100%').height('100%').backgroundColor('#070A12')
      .focusable(true).defaultFocus(true).focusOnTouch(true)
      .onDigitalCrown((event: CrownEvent) => this.handleCrown(event))
      .onAppear(() => {
        this.getUIContext().getFocusController().requestFocus('podjs-crown-target');
      })
  }
}
