/**
 * 
 */
package io.sinistral.proteus.annotations;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Indicates that this route should use a BlockingHandler
 */
@Retention(RUNTIME)
@Target({ TYPE, METHOD })
public @interface Blocking
{
	 boolean value() default true;
}

 