 
package io.sinistral.proteus.services;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import javax.ws.rs.HttpMethod;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.typesafe.config.Config;

import io.sinistral.proteus.server.MimeTypes;
import io.sinistral.proteus.server.endpoints.EndpointInfo;
import io.sinistral.proteus.server.swagger.ServerParameterExtension;
import io.swagger.jaxrs.ext.SwaggerExtension;
import io.swagger.jaxrs.ext.SwaggerExtensions;
import io.swagger.models.Info;
import io.swagger.models.Swagger;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.resource.ClassPathResourceManager;
import io.undertow.server.handlers.resource.Resource;
import io.undertow.server.handlers.resource.ResourceHandler;
import io.undertow.util.CanonicalPathUtils;
import io.undertow.util.Headers;
import io.undertow.util.Methods;

 
public class SwaggerService   extends BaseService implements Supplier<RoutingHandler>
{
	  
	private static Logger log = LoggerFactory.getLogger(SwaggerService.class.getCanonicalName());

	protected io.sinistral.proteus.server.swagger.Reader reader = null;
	
	protected final String swaggerResourcePath = "swagger";
	
	protected final String swaggerThemesPath = "swagger/themes";

	protected Swagger swagger = null;
	
	protected String swaggerSpec = null;
	
	protected String swaggerIndexHTML = null;
	
	
	@Inject
	@Named("swagger.basePath")
	protected String swaggerBasePath;
	
	@Inject
	@Named("swagger.theme")
	protected String swaggerTheme;
	
	@Inject
	@Named("swagger.specFilename")
	protected String specFilename;
	
	@Inject
	@Named("swagger.info")
	protected Config swaggerInfo;
	
	
	@Inject
	@Named("application.host")
	protected String host;
	
	@Inject
	@Named("application.name")
	protected String applicationName;
	
	@Inject
	@Named("application.port")
	protected Integer port;
	
	@Inject
	@Named("application.path")
	protected String applicationPath;
	
	@Inject
	protected RoutingHandler router;
	
	@Inject
	@Named("registeredEndpoints")
	protected Set<EndpointInfo> registeredEndpoints;
	 
	@Inject
	@Named("registeredControllers")
	protected Set<Class<?>> registeredControllers;
 
	protected ObjectMapper mapper = new ObjectMapper();
	
	protected ObjectWriter writer = null;
	
	protected ClassLoader serviceClassLoader = null;
	
	/**
	 * @param config
	 */
	public SwaggerService( )
	{ 
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		mapper.configure(DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT, true);
		mapper.configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true);
		mapper.configure(DeserializationFeature.EAGER_DESERIALIZER_FETCH,true); 
		mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
		mapper.setSerializationInclusion(Include.NON_NULL);

		mapper.registerModule(new Jdk8Module());

		
		writer = mapper.writerWithDefaultPrettyPrinter();
		writer = writer.without(SerializationFeature.WRITE_NULL_MAP_VALUES); 
	}

	 
	public void generateSwaggerSpec()
	{
		
		Set<Class<?>> classes = this.registeredControllers;
		
		List<SwaggerExtension> extensions = new ArrayList<>();
		
		extensions.add(new ServerParameterExtension());

		SwaggerExtensions.setExtensions(extensions);

		log.debug("Added SwaggerExtension: ServerParameterExtension");
		
		
		Swagger swagger = new Swagger();
		
		swagger.setBasePath(applicationPath);
		
		swagger.setHost(host+((port != 80 && port != 443) ? ":" + port : ""));
		
		Info info = new Info();
		
		if(swaggerInfo.hasPath("title"))
		{
			info.title(swaggerInfo.getString("title"));
		}
		
		if(swaggerInfo.hasPath("version"))
		{
			info.version(swaggerInfo.getString("version"));
		}
		
		if(swaggerInfo.hasPath("description"))
		{
			info.description(swaggerInfo.getString("description"));
		}
		
		swagger.setInfo(info);


		this.reader = new io.sinistral.proteus.server.swagger.Reader(swagger);
 
		classes.forEach( c -> this.reader.read(c));
		
		this.swagger = this.reader.getSwagger();
	 
		
		try
		{
			
			this.swaggerSpec = writer.writeValueAsString(this.swagger);
			
		} catch (Exception e)
		{
			log.error(e.getMessage(),e);
		}
 		
 		
	}


 

	/**
	 * @return the swagger
	 */
	public Swagger getSwagger()
	{
		return swagger;
	}

	/**
	 * @param swagger the swagger to set
	 */
	public void setSwagger(Swagger swagger)
	{
		this.swagger = swagger;
	}
	
	public void generateSwaggerHTML()
	{
		try
		{  
 
			this.serviceClassLoader = this.getClass().getClassLoader();
			
			final InputStream templateInputStream = this.getClass().getClassLoader().getResourceAsStream("swagger/index.html");
			
			byte[] templateBytes = IOUtils.toByteArray(templateInputStream); 
			
			String templateString = new String(templateBytes,Charset.defaultCharset());
			 
			String themePath = "swagger-ui.css";
			 
			if(!this.swaggerTheme.equals("default"))
			{
				themePath= "themes/theme-" + this.swaggerTheme + ".css"; 
			} 
			 
			templateString = templateString.replaceAll("\\{\\{ themePath \\}\\}", themePath);
			templateString = templateString.replaceAll("\\{\\{ swaggerBasePath \\}\\}", this.swaggerBasePath);
			templateString = templateString.replaceAll("\\{\\{ title \\}\\}",applicationName + " Swagger UI");
			templateString = templateString.replaceAll("\\{\\{ swaggerFullPath \\}\\}","//" + host + ((port != 80 && port != 443) ? ":" + port : "") + this.swaggerBasePath + ".json");

			this.swaggerIndexHTML = templateString; 
			
			
//			final InputStream resourceInputStream = this.getClass().getClassLoader().getResourceAsStream("swagger/swagger-ui-bundle.js");
//			
//			byte[] resourceBytes = IOUtils.toByteArray(resourceInputStream); 
//			
//			String resourceString = new String(resourceBytes,Charset.defaultCharset());
//			
//			System.out.println("resource: " + resourceString);
//			
		 
			

		
		} catch (Exception e)
		{ 
			log.error(e.getMessage(),e);
		}
	}
	
	public RoutingHandler get()
	{
		
		RoutingHandler router = new RoutingHandler();
		
		String pathTemplate = this.swaggerBasePath + ".json";
		
 		
		router.add(HttpMethod.GET, pathTemplate, new HttpHandler(){

			@Override
			public void handleRequest(HttpServerExchange exchange) throws Exception
			{
 
				exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, MimeTypes.APPLICATION_JSON_TYPE);
 

				exchange.getResponseSender().send(swaggerSpec);
				
			}
			
		});
		
   
		this.registeredEndpoints.add(EndpointInfo.builder().withConsumes("*/*").withPathTemplate(pathTemplate).withControllerName("Swagger").withMethod(Methods.GET).withProduces(MimeTypes.APPLICATION_JSON_TYPE).build());
		 
		pathTemplate =  this.swaggerBasePath;
		
 		
		router.add(HttpMethod.GET, pathTemplate , new HttpHandler(){

			@Override
			public void handleRequest(HttpServerExchange exchange) throws Exception
			{
 
 
 				exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, MimeTypes.TEXT_HTML_TYPE);
 				exchange.getResponseSender().send(swaggerIndexHTML);
				
			}
			
		});
 
		this.registeredEndpoints.add(EndpointInfo.builder().withConsumes("*/*").withProduces("text/html").withPathTemplate(pathTemplate).withControllerName("Swagger").withMethod(Methods.GET).build());
 
		pathTemplate = this.swaggerBasePath + "/themes/*";
		
		router.add(HttpMethod.GET, pathTemplate, new HttpHandler(){

			@Override
			public void handleRequest(HttpServerExchange exchange) throws Exception
			{
 
				String canonicalPath = CanonicalPathUtils.canonicalize((exchange.getRelativePath()));
						
				canonicalPath = swaggerThemesPath + canonicalPath.split(swaggerBasePath+"/themes")[1]; 
				
				try(final InputStream resourceInputStream = serviceClassLoader.getResourceAsStream(canonicalPath))
				{
				
					byte[] resourceBytes = IOUtils.toByteArray(resourceInputStream); 
				
					exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, io.sinistral.proteus.server.MediaType.TEXT_CSS_UTF8.toString());
				
					exchange.getResponseSender().send(ByteBuffer.wrap(resourceBytes));
				
				}
			}
			
		});
		 
		
		this.registeredEndpoints.add(EndpointInfo.builder().withConsumes("*/*").withProduces("text/css").withPathTemplate(pathTemplate).withControllerName("Swagger").withMethod(Methods.GET).build());

 		
		try
		{
	 

			 pathTemplate =  this.swaggerBasePath + "/*";
			 
			 router.add(HttpMethod.GET, pathTemplate, new HttpHandler(){

					@Override
					public void handleRequest(HttpServerExchange exchange) throws Exception
					{
						 
						String canonicalPath = CanonicalPathUtils.canonicalize((exchange.getRelativePath()));
					 
						canonicalPath = swaggerResourcePath + canonicalPath.split(swaggerBasePath)[1]; 
						 
						System.out.println("canonicalPath: " + canonicalPath);
						
						try(final InputStream resourceInputStream = serviceClassLoader.getResourceAsStream(canonicalPath))
						{
						
							byte[] resourceBytes = IOUtils.toByteArray(resourceInputStream); 
						
							exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, io.sinistral.proteus.server.MediaType.APPLICATION_JAVASCRIPT_UTF8.toString());
						
							exchange.getResponseSender().send(ByteBuffer.wrap(resourceBytes));
						
						}
						
					}
					
				});
			

				
			 this.registeredEndpoints.add(EndpointInfo.builder().withConsumes("*/*").withProduces("*/*").withPathTemplate(pathTemplate).withControllerName("Swagger").withMethod(Methods.GET).build());

 

		} catch (Exception e)
		{
			log.error(e.getMessage(),e);
		}
 		  
		return router; 
	}

	 

	/* (non-Javadoc)
	 * @see com.google.common.util.concurrent.AbstractIdleService#startUp()
	 */
	@Override
	protected void startUp() throws Exception
	{
		// TODO Auto-generated method stub
		
		
		this.generateSwaggerSpec();
		this.generateSwaggerHTML();
 
		log.debug("\nSwagger Spec:\n" +  writer.writeValueAsString(this.swagger));

		router.addAll(this.get()); 
	}

	/* (non-Javadoc)
	 * @see com.google.common.util.concurrent.AbstractIdleService#shutDown()
	 */
	@Override
	protected void shutDown() throws Exception
	{
		// TODO Auto-generated method stub
		
	}
	
}