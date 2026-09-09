package dev.podjs.androidwatch;
import android.app.Activity; import android.os.Bundle; import android.view.MotionEvent; import dev.podjs.runtime.PodRuntimeView;
public final class MainActivity extends Activity {
  private PodRuntimeView pod;
  @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(pod!=null)pod.notificationPermissionResult(code,results);}
  private float swipeStartX, swipeStartY;
  private boolean swipeActive, swipeClaimed, platformSwipeDismiss;
  @Override public void onCreate(Bundle b){platformSwipeDismiss=PodRuntimeView.requestPlatformSwipeDismiss(this);super.onCreate(b);getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK));pod=new PodRuntimeView(this,"android-watch");pod.captureNotificationIntent(getIntent());setContentView(pod);if(PodRuntimeView.ACTION_COMPANION_SETTINGS.equals(getIntent().getAction()))pod.post(pod::showCompanionSettings);}
  @Override protected void onNewIntent(android.content.Intent intent){super.onNewIntent(intent);setIntent(intent);pod.captureNotificationIntent(intent);if(PodRuntimeView.ACTION_COMPANION_SETTINGS.equals(intent.getAction()))pod.showCompanionSettings();}
  @Override public boolean onCreateOptionsMenu(android.view.Menu menu){menu.add(0,0x5053,0,"手机同步");return true;}
  @Override public boolean onOptionsItemSelected(android.view.MenuItem item){if(item.getItemId()==0x5053){pod.showCompanionSettings();return true;}return super.onOptionsItemSelected(item);}
  @Override protected void onResume(){super.onResume();pod.setLifecycle(0);}
  @Override protected void onPause(){pod.setLifecycle(1);super.onPause();}
  @Override protected void onStop(){pod.setLifecycle(2);super.onStop();}
  @Override public boolean dispatchTouchEvent(MotionEvent e) {
    boolean internal = pod != null && pod.canNavigateBack();
    if (pod != null && pod.getParent() != null)
      pod.getParent().requestDisallowInterceptTouchEvent(internal || !platformSwipeDismiss);
    // OPPO owns root gestures. Other hosts recognize only a root edge swipe
    // and invoke the same system-back path as the hardware button on release.
    if (pod != null && !internal && !platformSwipeDismiss) {
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
  @Override public boolean dispatchGenericMotionEvent(MotionEvent e){return pod!=null&&pod.handleScrollMotion(e)||super.dispatchGenericMotionEvent(e);}
  @Override public void onBackPressed(){if(!pod.sendBack())super.onBackPressed();}
}
