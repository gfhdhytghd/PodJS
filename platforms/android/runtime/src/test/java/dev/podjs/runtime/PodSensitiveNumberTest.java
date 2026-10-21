package dev.podjs.runtime;
import android.app.Application;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import androidx.test.core.app.ApplicationProvider;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,application=Application.class)
public class PodSensitiveNumberTest {
 EditText input(){return new EditText(ApplicationProvider.getApplicationContext());}
 JSONObject options()throws Exception{return new JSONObject().put("inputType","number").put("sensitive",true);}
 @Test public void actualPromptConfigurationFiltersDigitsBoundsLengthAndDisablesLearningAndAutofill()throws Exception {
  EditText input=input();PodServices.configureSensitiveNumber(input,options(),null);input.setText("12a34-567890");
  assertEquals("12345678",input.getText().toString());assertEquals(InputType.TYPE_CLASS_NUMBER,input.getInputType()&InputType.TYPE_MASK_CLASS);
  assertTrue((input.getImeOptions()&EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)!=0);assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO,input.getImportantForAutofill());
  EditText bounded=input();PodServices.configureSensitiveNumber(bounded,options().put("maxLength",4),null);bounded.setText("123456");assertEquals("1234",bounded.getText().toString());
 }
 @Test public void sensitiveNumbersRejectRestorationAndInvalidBounds()throws Exception {
  try{PodServices.configureSensitiveNumber(input(),options(),"saved-draft");fail();}catch(IllegalArgumentException expected){assertEquals("sensitive_input_not_restorable",expected.getMessage());}
  for(int length:new int[]{0,33,-1})try{PodServices.configureSensitiveNumber(input(),options().put("maxLength",length),null);fail();}catch(IllegalArgumentException expected){assertEquals("invalid_input_limit",expected.getMessage());}
 }
 @Test public void ordinaryRestorableTextKeepsItsExistingBehavior()throws Exception {
  EditText input=input();PodServices.configureSensitiveNumber(input,new JSONObject().put("inputType","number"),"draft");input.setText("mixed text 123456789");assertEquals("mixed text 123456789",input.getText().toString());
 }
}
