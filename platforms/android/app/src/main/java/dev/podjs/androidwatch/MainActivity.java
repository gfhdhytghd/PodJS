package dev.podjs.androidwatch;
import android.app.Activity; import android.os.Bundle; import android.view.MotionEvent; import dev.podjs.runtime.PodRuntimeView; import dev.podjs.runtime.PodStartupTrace;
public final class MainActivity extends Activity {
  private PodRuntimeView pod;
  private dev.podjs.runtime.PodSplash splash;
  private Bundle pendingState;
  private boolean resumed;
  private static final String POD_SAVED_STATE = "dev.podjs.activity-state";
  @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(pod!=null)pod.notificationPermissionResult(code,results);}
  private float swipeStartX, swipeStartY;
  private boolean swipeActive, swipeClaimed, platformSwipeDismiss;
  @Override public void onCreate(Bundle b){long began=PodStartupTrace.now();try{splash=dev.podjs.runtime.PodSplash.install(this);platformSwipeDismiss=PodRuntimeView.requestPlatformSwipeDismiss(this);super.onCreate(b);pendingState=b;if(dev.podjs.runtime.PodTakeover.showRecoveryIfNeeded(this,this::startRuntime))return;startRuntime();}finally{PodStartupTrace.stage("activityOnCreate",began);}}
  private void startRuntime(){if(pod!=null||isFinishing()||isDestroyed())return;long began=PodStartupTrace.now();Bundle b=pendingState;pendingState=null;pod=new PodRuntimeView(this,"android-watch");pod.restoreActivityState(b==null?null:b.getString(POD_SAVED_STATE),getLastNonConfigurationInstance() instanceof String[]&&((String[])getLastNonConfigurationInstance()).length==2?((String[])getLastNonConfigurationInstance())[0]:getLastNonConfigurationInstance() instanceof String?(String)getLastNonConfigurationInstance():null);pod.restoreModalState(b==null?null:b.getString("dev.podjs.modal-state"),getLastNonConfigurationInstance() instanceof String[]&&((String[])getLastNonConfigurationInstance()).length==2?((String[])getLastNonConfigurationInstance())[1]:null);pod.captureNotificationIntent(getIntent());setContentView(pod);if(resumed)pod.setLifecycle(0);if(PodRuntimeView.ACTION_COMPANION_SETTINGS.equals(getIntent().getAction()))pod.post(pod::showCompanionSettings);PodStartupTrace.stage("activityStartRuntime",began);}
  @Override protected void onDestroy(){if(splash!=null)splash.close(this);super.onDestroy();}
  @Override protected void onSaveInstanceState(Bundle out){if(pod!=null){out.putString(POD_SAVED_STATE,pod.savedActivityState());out.putString("dev.podjs.modal-state",pod.savedModalState());}else if(pendingState!=null){out.putString(POD_SAVED_STATE,pendingState.getString(POD_SAVED_STATE));out.putString("dev.podjs.modal-state",pendingState.getString("dev.podjs.modal-state"));}super.onSaveInstanceState(out);}
  @Override public Object onRetainNonConfigurationInstance(){return pod==null?getLastNonConfigurationInstance():new String[]{pod.retainedActivityState(),pod.retainedModalState()};}
  @Override protected void onNewIntent(android.content.Intent intent){super.onNewIntent(intent);setIntent(intent);if(pod==null)return;pod.captureNotificationIntent(intent);if(PodRuntimeView.ACTION_COMPANION_SETTINGS.equals(intent.getAction()))pod.showCompanionSettings();}
  @Override public boolean onCreateOptionsMenu(android.view.Menu menu){menu.add(0,0x5053,0,"手机同步");return true;}
  @Override public boolean onOptionsItemSelected(android.view.MenuItem item){if(item.getItemId()==0x5053){if(pod!=null)pod.showCompanionSettings();return true;}return super.onOptionsItemSelected(item);}
  @Override protected void onResume(){super.onResume();resumed=true;if(pod!=null)pod.setLifecycle(0);}
  @Override protected void onPause(){resumed=false;if(pod!=null)pod.setLifecycle(1);super.onPause();}
  @Override protected void onStop(){resumed=false;if(pod!=null)pod.setLifecycle(2);super.onStop();}
  @Override public boolean dispatchTouchEvent(MotionEvent e) {
    boolean swipeAllowed = pod != null && pod.canSwipeDismiss();
    if (!swipeAllowed) { swipeActive = false; swipeClaimed = false; }
    if (pod != null && pod.getParent() != null)
      pod.getParent().requestDisallowInterceptTouchEvent(!swipeAllowed || !platformSwipeDismiss);
    // OPPO owns root gestures. Other hosts recognize only a root edge swipe
    // and invoke the same system-back path as the hardware button on release.
    if (pod != null && swipeAllowed && !platformSwipeDismiss) {
      int action = e.getActionMasked();
      float dx = e.getX() - swipeStartX, dy = Math.abs(e.getY() - swipeStartY);
      float slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
      if (action == MotionEvent.ACTION_DOWN) {
        swipeStartX = e.getX(); swipeStartY = e.getY(); swipeClaimed = false;
        swipeActive = swipeStartX <= pod.getWidth() * 0.25f;
      } else if (action == MotionEvent.ACTION_MOVE && swipeActive) {
        if (!swipeClaimed && dy > slop && dy > Math.abs(dx)) swipeActive = false;
        if (!swipeClaimed && dx > slop && dx > dy * 1.2f) {
          MotionEvent cancel = MotionEvent.obtain(e); cancel.setAction(MotionEvent.ACTION_CANCEL);
          super.dispatchTouchEvent(cancel); cancel.recycle(); swipeClaimed = true;
        }
        if (swipeClaimed) return true;
      } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
        boolean claimed = swipeClaimed;
        boolean commit = claimed && action == MotionEvent.ACTION_UP && dx >= pod.getWidth() * 0.40f;
        swipeActive = false; swipeClaimed = false;
        if (commit) onBackPressed();
        if (claimed) return true;
      }
    }
    return super.dispatchTouchEvent(e);
  }
  @Override public boolean dispatchKeyEvent(android.view.KeyEvent e){return dev.podjs.runtime.PodMediaVolumeKeys.dispatch(this,e)||super.dispatchKeyEvent(e);}
  @Override public boolean dispatchGenericMotionEvent(MotionEvent e){return pod!=null&&pod.handleScrollMotion(e)||super.dispatchGenericMotionEvent(e);}
  @Override public void onBackPressed(){if(pod==null||!pod.sendBack())super.onBackPressed();}
}
