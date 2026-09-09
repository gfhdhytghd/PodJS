import { common } from '@kit.AbilityKit';
import { CompanionPairings, CompanionPairingInfo } from '@podjs/companion';
import { CompanionPairedAttempt, CompanionSyncAttempt, CompanionStreamTimer, NativeCompanionBleAcceptor, NativeCompanionSyncCrypto, requestCompanionBluetoothPermission } from '@podjs/companion';
import { nativeInstalledSyncPairings } from './NativeInstalledSync';
import { installedSyncStatus, installedSyncIsForeground, disconnectInstalledSync, connectInstalledSync, onInstalledSyncBackground } from './NativeInstalledSyncConnection';

class InstalledConnectionTimer implements CompanionStreamTimer {
  schedule(ms: number, expire: () => void): () => void {
    const id = setTimeout(expire, ms); return () => clearTimeout(id);
  }
}

@Component
export struct InstalledSyncPanel {
  onClose: () => void = () => {};
  onPair: () => void = () => {};
  private generation: number = 0;
  private owner: CompanionPairings | null = null;
  private timer: number = -1;
  private removeBackground: () => void = () => {};
  private pendingConnection: boolean = false;
  @State private records: CompanionPairingInfo[] = [];
  @State private busy: boolean = false;
  @State private loaded: boolean = false;
  @State private status: string = '';
  @State private connected: boolean = false;
  @State private connectionLabel: string = '尚未连接';
  aboutToAppear(): void {
    this.generation++; this.refresh();
    this.removeBackground = onInstalledSyncBackground(() => this.cancelPending());
    this.timer = setInterval(() => this.readConnection(), 1000);
  }
  aboutToDisappear(): void {
    this.removeBackground(); this.cancelPending();
    if (this.timer !== -1) clearInterval(this.timer); this.timer = -1;
  }
  private cancelPending(): void {
    this.generation++;
    if (this.pendingConnection) disconnectInstalledSync();
    this.pendingConnection = false; this.busy = false; this.readConnection();
  }
  private async waitForPeer(record: CompanionPairingInfo): Promise<void> {
    if (this.busy || this.connected || record.phase !== 'approved' || !installedSyncIsForeground()) return;
    const epoch = this.generation, peer = record.peer;
    this.busy = true; this.pendingConnection = true; this.status = '';
    try {
      const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
      const allowed = await requestCompanionBluetoothPermission(context, () => !this.current(epoch));
      if (!this.current(epoch)) return;
      if (!allowed) { this.status = '需要蓝牙权限，请允许后重试。'; return; }
      const owner = await nativeInstalledSyncPairings(context);
      if (!this.current(epoch)) return;
      const attempt = new CompanionPairedAttempt(owner,
        new CompanionSyncAttempt(new NativeCompanionBleAcceptor(null, 120000), new NativeCompanionSyncCrypto(),
          new InstalledConnectionTimer(), 120000), new InstalledConnectionTimer(), 120000);
      this.status = '请在对端发现并选择这只手表，双方须选择对应配对设备。';
      await connectInstalledSync(context, attempt, peer, false);
      if (this.current(epoch)) this.status = '已认证连接。可返回应用同步，前台会话最长两分钟，不自动重连。';
    } catch (_) {
      if (this.current(epoch)) this.status = '连接未完成，请确认对端配对和蓝牙后重试。';
    } finally {
      if (epoch === this.generation) { this.pendingConnection = false; this.busy = false; this.readConnection(); }
    }
  }
  private current(epoch: number): boolean { return epoch === this.generation && installedSyncIsForeground(); }
  private readConnection(): void {
    const value = installedSyncStatus();
    this.connected = value.phase === 'connected' || value.phase === 'connecting';
    this.connectionLabel = value.phase === 'connected' ? '已连接：' + value.peer :
      value.phase === 'connecting' ? '正在连接：' + value.peer :
      value.phase === 'failed' ? '连接失败，请重试' : '尚未连接';
  }
  private async refresh(): Promise<void> {
    if (this.busy || !installedSyncIsForeground()) return;
    const epoch = this.generation; this.busy = true; this.status = ''; this.readConnection();
    try {
      const owner = await nativeInstalledSyncPairings(this.getUIContext().getHostContext() as common.UIAbilityContext);
      if (!this.current(epoch)) return;
      this.owner = owner; await owner.recover();
      const records = await owner.list();
      if (this.current(epoch)) { this.records = records; this.loaded = true; this.readConnection(); }
    } catch (_) {
      if (this.current(epoch)) this.status = '配对记录暂不可用。请返回前台后重试恢复。';
    } finally { if (epoch === this.generation) this.busy = false; }
  }
  private confirmRevoke(record: CompanionPairingInfo): void {
    if (this.busy || !installedSyncIsForeground()) return;
    const epoch = this.generation, peer = record.peer;
    this.getUIContext().showAlertDialog({ title: '撤销配对？',
      message: peer + '\n将断开连接并删除本机配对凭据，不删除笔记或文件副本。再次连接需要重新配对。',
      primaryButton: { value: '保留', action: () => {} },
      secondaryButton: { value: '撤销', action: () => { if (this.current(epoch)) this.revoke(peer); } }
    });
  }
  private async revoke(peer: string): Promise<void> {
    if (this.busy || this.owner === null || !installedSyncIsForeground()) return;
    const epoch = this.generation; this.busy = true; this.status = '';
    if (installedSyncStatus().peer === peer) disconnectInstalledSync();
    this.readConnection();
    try {
      await this.owner.revoke(peer);
      const records = await this.owner.list();
      if (this.current(epoch)) { this.records = records; this.status = '已撤销本机配对。'; }
    } catch (_) {
      if (this.current(epoch)) this.status = '撤销清理未完成，请恢复并刷新后重试。';
    } finally { if (epoch === this.generation) this.busy = false; }
  }
  build() {
    Scroll() {
      Column({ space: 12 }) {
        Text('同步设备').fontSize(18).fontWeight(FontWeight.Bold).fontColor('#F6F8FC')
        Button('返回应用').width('100%').onClick(() => this.onClose())
        Button('配对新设备').enabled(!this.busy && !this.connected).width('100%').onClick(() => this.onPair())
        Text(this.connectionLabel).fontSize(14).fontColor('#A9B3C7').width('100%')
        if (this.connected) {
          Button('断开连接').width('100%').onClick(() => { disconnectInstalledSync(); this.readConnection(); })
        }
        Button(this.busy ? '正在读取' : '恢复并刷新').enabled(!this.busy).width('100%')
          .backgroundColor('#83B7FF').fontColor('#070A12').onClick(() => this.refresh())
        if (this.busy) {
          Button('取消等待').width('100%').onClick(() => { this.cancelPending(); this.status = '已取消。'; })
        }
        if (this.status.length > 0) { Text(this.status).fontSize(14).fontColor('#F6F8FC').width('100%') }
        if (this.loaded && this.records.length === 0) {
          Text('暂无配对设备，请选择配对新设备。').fontSize(14).fontColor('#A9B3C7').width('100%')
        }
        ForEach(this.records, (record: CompanionPairingInfo) => {
          Column({ space: 8 }) {
            Text(record.peer).fontSize(14).fontColor('#F6F8FC').width('100%').wordBreak(WordBreak.BREAK_ALL)
            Text(record.phase === 'approved' ? '已配对，不代表已连接' : '配对清理未完成，请恢复')
              .fontSize(14).fontColor('#A9B3C7').width('100%')
            if (record.phase === 'approved') {
              Button('等待此设备连接').enabled(!this.busy && !this.connected).width('100%')
                .backgroundColor('#83B7FF').fontColor('#070A12').onClick(() => this.waitForPeer(record))
            }
            Button('撤销配对').enabled(!this.busy).width('100%').fontColor('#D95661')
              .backgroundColor('#070A12').accessibilityText('撤销与 ' + record.peer + ' 的配对')
              .onClick(() => this.confirmRevoke(record))
          }.width('100%').alignItems(HorizontalAlign.Start)
        }, (record: CompanionPairingInfo) => record.peer)
      }.width('100%').padding({ top: 32, bottom: 40, left: 32, right: 32 })
    }.width('100%').height('100%').backgroundColor('#070A12')
      .id('podjs-sync-settings').focusable(true)
      .onAppear(() => this.getUIContext().getFocusController().requestFocus('podjs-sync-settings'))
  }
}
