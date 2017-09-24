package jazmin.driver.mq;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author yama
 *
 */
@Target(ElementType.METHOD)  
@Retention(RetentionPolicy.RUNTIME)  
@Documented
@Inherited  
public @interface TopicSubscriberDefine{
	/**
	 * name of notification 
	 */
	String topic();
}
