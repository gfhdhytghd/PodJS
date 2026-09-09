import { common } from '@kit.AbilityKit';
import image from '@ohos.multimedia.image';
import { CompanionPairingInvitation, CompanionPairingInvitationLease, CompanionStreamTimer,
  CompanionInitialPairing, CompanionPairingApprovalGate, CompanionPairingApprovalRequest,
  CompanionSyncAttempt, NativeCompanionBleAcceptor, NativeCompanionSyncCrypto,
  requestCompanionBluetoothPermission, renderCompanionPairingQr } from '@podjs/companion';
import { nativeInstalledSyncConnectionClient, nativeInstalledSyncPairings } from './NativeInstalledSync';
import { installedSyncIsForeground, onInstalledSyncBackground } from './NativeInstalledSyncConnection';

class InstalledInvitationTimer implements CompanionStreamTimer {
  schedule(ms: number, expire: () => void): () => void {
    const id = setTimeout(expire, ms); return () => clearTimeout(id);
  }
}
@Component
export struct InstalledPairingPanel {
  onClose: () => void = () => {};
  private generation: number = 0;
  private invitation: CompanionPairingInvitation | null = null;
  private lease: CompanionPairingInvitationLease | null = null;
  private pairing: CompanionInitialPairing | null = null;
  private approval: CompanionPairingApprovalGate | null = null;
  private answer: ((approved: boolean) => void) | null = null;
  private removeBackground: () => void = () => {};
  @State private bitmap: image.PixelMap | null = null;
  @State private device: string = '';
  @State private peer: string = '';
  @State private busy: boolean = false;
  @State private status: string = '';
  @State private confirmation: CompanionPairingApprovalRequest | null = null;
  aboutToAppear(): void { this.removeBackground = onInstalledSyncBackground(() => this.cancel()); }
  aboutToDisappear(): void { this.removeBackground(); this.clear(); }
  private current(epoch: number): boolean { return epoch === this.generation && installedSyncIsForeground(); }
  private releaseImage(): void {
    const bitmap = this.bitmap; this.bitmap = null;
    if (bitmap !== null) bitmap.release().catch(() => {});
  }
  private clear(): void {
    this.generation++;
    const pairing = this.pairing; this.pairing = null; pairing?.cancel();
    this.approval?.dismiss(); this.approval = null; this.answer = null; this.confirmation = null;
    const lease = this.lease; this.lease = null; lease?.close();
    this.invitation?.close(); this.invitation = null;
    this.releaseImage(); this.device = ''; this.peer = ''; this.busy = false;
  }
  private cancel(): void {
    const mayExist = this.pairing !== null && this.pairing.localCredentialMayExist();
    this.clear();
    this.status = mayExist ? '已取消，凭据可能已保存。请返回检查双方配对记录。' : '临时邀请已清除。';
  }
  private async createInvitation(): Promise<void> {
    if (this.busy || !installedSyncIsForeground()) return;
    this.clear(); const epoch = this.generation; this.busy = true; this.status = '';
    let invitation: CompanionPairingInvitation | null = null;
    try {
      const client = await nativeInstalledSyncConnectionClient(this.getUIContext().getHostContext() as common.UIAbilityContext);
      if (!this.current(epoch)) return;
      invitation = await CompanionPairingInvitation.create(client.appId(), client.localId(), Date.now(), new NativeCompanionSyncCrypto());
      if (!this.current(epoch)) { invitation.close(); return; }
      this.invitation = invitation;
      const lease = new CompanionPairingInvitationLease(invitation, { now: () => Date.now() }, new InstalledInvitationTimer(), () => {
        if (epoch !== this.generation) return;
        this.releaseImage();
        if (this.pairing === null) { this.clear(); this.status = '邀请已过期，请重新生成。'; }
      });
      this.lease = lease; lease.assertActive();
      const bitmap = await renderCompanionPairingQr(lease);
      if (!this.current(epoch)) { await bitmap.release(); return; }
      try { lease.assertActive(); } catch (error) { await bitmap.release(); throw error as Error; }
      this.bitmap = bitmap; this.device = client.localId();
      this.status = '让对端扫码，填写其显示的应用设备标识，再开始等待。';
    } catch (_) {
      invitation?.close();
      if (epoch === this.generation) { this.clear(); this.status = '生成邀请失败，请重试。'; }
    } finally { if (epoch === this.generation) this.busy = false; }
  }
  private async waitForPeer(): Promise<void> {
    if (this.busy || this.invitation === null || this.lease === null || !installedSyncIsForeground()) return;
    const peer = this.peer.trim(), invitation = this.invitation, lease = this.lease, epoch = this.generation;
    if (!/^[A-Za-z0-9_.:-]{1,128}$/.test(peer) || peer === this.device) { this.status = '请填写对端显示的应用设备标识。'; return; }
    this.busy = true; this.status = '正在检查蓝牙权限。';
    let pairing: CompanionInitialPairing | null = null;
    try {
      const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
      const granted = await requestCompanionBluetoothPermission(context, () => !this.current(epoch));
      if (!this.current(epoch)) return;
      if (!granted) { this.status = '需要蓝牙权限，尚未开始配对。'; return; }
      const owner = await nativeInstalledSyncPairings(context);
      if (!this.current(epoch)) return;
      lease.assertActive();
      const crypto = new NativeCompanionSyncCrypto();
      const gate = new CompanionPairingApprovalGate({
        show: (request: CompanionPairingApprovalRequest, answer: (approved: boolean) => void) => {
          if (!this.current(epoch)) { answer(false); return; }
          this.confirmation = request; this.answer = answer; this.status = '请核对双方身份，确认这是你要配对的设备。';
        },
        hide: () => { if (epoch === this.generation) { this.confirmation = null; this.answer = null; } }
      });
      this.approval = gate;
      pairing = new CompanionInitialPairing(invitation, lease, owner,
        new CompanionSyncAttempt(new NativeCompanionBleAcceptor(null, 120000), crypto, new InstalledInvitationTimer(), 120000), crypto, gate);
      this.pairing = pairing; this.status = '正在等待。请让对端发现并选择手表；广播最多持续三十秒。';
      await pairing.start(this.device, peer, false);
      if (!this.current(epoch)) return;
      this.clear(); this.status = '双方已保存配对。返回设备列表建立数据连接。';
    } catch (_) {
      if (epoch !== this.generation) return;
      const mayExist = pairing !== null && pairing.localCredentialMayExist();
      this.clear(); this.status = mayExist ? '凭据可能已保存，请检查双方记录，不要直接覆盖。' : '配对未完成，请检查标识、权限和对端后重新生成邀请。';
    } finally { if (epoch === this.generation) this.busy = false; }
  }
  private respond(request: CompanionPairingApprovalRequest, approved: boolean): void {
    if (this.confirmation !== request || !installedSyncIsForeground()) return;
    const answer = this.answer; this.answer = null;
    if (answer !== null) { answer(approved); if (approved) this.status = '本机已确认，正在等待对端确认并保存。'; }
  }
  build() {
    Scroll() {
      Column({ space: 12 }) {
        Text('配对新设备').fontSize(18).fontColor('#F6F8FC').fontWeight(FontWeight.Bold)
        Button('返回设备列表').width('100%').onClick(() => this.onClose())
        Text('二维码含临时凭据，请勿截图转发。最多五分钟有效，离开页面即清除。').fontSize(14).fontColor('#A9B3C7').width('100%')
        Button('生成邀请').enabled(!this.busy).width('100%').backgroundColor('#83B7FF').fontColor('#070A12').onClick(() => this.createInvitation())
        if (this.bitmap !== null) {
          Image(this.bitmap).width('100%').aspectRatio(1).objectFit(ImageFit.Contain)
            .interpolation(ImageInterpolation.None).padding(12).backgroundColor('#FFFFFF')
            .accessibilityText('临时配对二维码，仅供信任的设备扫描')
          Text('本机：' + this.device).fontSize(14).fontColor('#F6F8FC').wordBreak(WordBreak.BREAK_ALL).width('100%')
          TextInput({ placeholder: '对端应用设备标识', text: this.peer }).enabled(!this.busy)
            .accessibilityText('对端应用设备标识').onChange((value: string) => { this.peer = value; })
          Button('等待对端配对').enabled(!this.busy).width('100%').onClick(() => this.waitForPeer())
        }
        if (this.confirmation !== null) {
          ForEach([this.confirmation], (request: CompanionPairingApprovalRequest) => {
            Column({ space: 8 }) {
              Text('应用：' + request.app + '\n本机：' + request.local + '\n对端：' + request.peer)
                .fontSize(14).fontColor('#F6F8FC').wordBreak(WordBreak.BREAK_ALL).width('100%')
              Button('确认配对').width('100%').onClick(() => this.respond(request, true))
              Button('拒绝配对').width('100%').onClick(() => this.respond(request, false))
            }
          }, (request: CompanionPairingApprovalRequest) => request.invitationId)
        }
        if (this.busy || this.bitmap !== null) { Button('取消邀请').width('100%').onClick(() => this.cancel()) }
        Text(this.status).fontSize(14).fontColor('#F6F8FC').width('100%')
      }.width('100%').padding({ top: 32, bottom: 40, left: 32, right: 32 })
    }.width('100%').height('100%').backgroundColor('#070A12').id('podjs-pairing-settings').focusable(true)
      .onAppear(() => this.getUIContext().getFocusController().requestFocus('podjs-pairing-settings'))
  }
}
