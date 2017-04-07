/**
 * 
 */
package com.wurrly.modules;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
 

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.AbstractModule;
import com.google.inject.Scope;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Names;
import com.wurrly.Application.BaseHandlers;
import com.wurrly.server.route.RouteInfo;

import io.undertow.server.RoutingHandler;

/**
 * @author jbauer
 *
 */
@Singleton
public class RoutingModule extends AbstractModule  
{
	private static Logger log = LoggerFactory.getLogger(RoutingModule.class.getCanonicalName());

	protected Set<RouteInfo> registeredRoutes = new TreeSet<>();
	protected Set<Class<?>> registeredControllers = new HashSet<>();

	@Override
	protected void configure()
	{
		RoutingHandler router = new RoutingHandler().setFallbackHandler(BaseHandlers::notFoundHandler);
		 		
		this.bind(RoutingHandler.class).toInstance(router); 
		
		this.bind(RoutingModule.class).toInstance(this);
		
		this.bind(new TypeLiteral<Set<Class<?>>>() {}).annotatedWith(Names.named("registeredControllers")).toInstance(registeredControllers);
		
	}

	/**
	 * @return the registeredRoutes
	 */
	public Set<RouteInfo> getRegisteredRoutes()
	{
		return registeredRoutes;
	}

	/**
	 * @return the registeredControllers
	 */
	public Set<Class<?>> getRegisteredControllers()
	{
		return registeredControllers;
 
	}

	

}
