/**
 * 
 */
package io.sinistral.proteus.server.handlers;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.lang.model.element.Modifier;
import javax.ws.rs.CookieParam;
import javax.ws.rs.FormParam;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import org.apache.commons.lang3.StringUtils;
import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.jsoniter.spi.TypeLiteral;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import io.sinistral.proteus.annotations.Blocking;
import io.sinistral.proteus.server.Extractors;
import io.sinistral.proteus.server.ServerRequest;
import io.sinistral.proteus.server.ServerResponse;
import io.sinistral.proteus.server.endpoints.EndpointInfo;
import io.swagger.annotations.Api;
import io.undertow.server.HandlerWrapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.RoutingHandler;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import net.openhft.compiler.CompilerUtils;

/**
 * Generates code and compiles a <code>Supplier<RoutingHandler></code> class from the target class's methods that are annotated with a JAX-RS method annotation (i.e. <code>javax.ws.rs.GET</code>)
 * 
 * @author jbauer
 */
public class HandlerGenerator
{

	private static Logger log = LoggerFactory.getLogger(HandlerGenerator.class.getCanonicalName());

	private static final Pattern TYPE_NAME_PATTERN = Pattern.compile("(java\\.util\\.[A-Za-z]+)<([^>]+)", Pattern.DOTALL | Pattern.UNIX_LINES);
	private static final Pattern CONCURRENT_TYPE_NAME_PATTERN = Pattern.compile("(java\\.util\\.concurrent\\.[A-Za-z]+)<([^>]+)", Pattern.DOTALL | Pattern.UNIX_LINES);

	public enum StatementParameterType
	{
		STRING, LITERAL, TYPE, RAW
	}

	/**
	 * Enum class that assists in code generation for different method parameter types
	 */
	public enum TypeHandler
	{

		LongType("Long $L = $T.longValue(exchange,$S)", false, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.class, StatementParameterType.STRING),
		IntegerType("Integer $L = $T.integerValue(exchange,$S)", false, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.class, StatementParameterType.STRING),
		StringType("String $L =  $T.string(exchange,$S)", false, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.class, StatementParameterType.STRING),
		BooleanType("Boolean $L =  $T.booleanValue(exchange,$S)", false, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.class, StatementParameterType.STRING),
		FilePathType("$T $L = $T.filePath(exchange,$S)", true, java.nio.file.Path.class, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.class, StatementParameterType.STRING),
		AnyType("$T $L = $T.any(exchange)", true, com.jsoniter.any.Any.class, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.class),
		JsonIteratorType("$T $L = $T.jsonIterator(exchange)", true, com.jsoniter.JsonIterator.class, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.class),
		ModelType("$T $L = io.sinistral.proteus.server.Extractors.model(exchange,$L)", true, StatementParameterType.TYPE, StatementParameterType.LITERAL, StatementParameterType.LITERAL),

		// EnumType("$T $L = $T.enumValue(exchange,$T.class,$S)", true, StatementParameterType.TYPE, StatementParameterType.LITERAL,io.sinistral.proteus.server.Extractors.class, StatementParameterType.TYPE, StatementParameterType.STRING),
		ByteBufferType("$T $L =  $T.byteBuffer(exchange,$S)", true, java.nio.ByteBuffer.class, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.class, StatementParameterType.STRING),
		DateType("$T $L =  $T.date(exchange,$S)", false, java.util.Date.class, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.class, StatementParameterType.STRING),
		ZonedDateTimeType("$T $L = $T.zonedDateTime(exchange,$S)", false, java.time.ZonedDateTime.class, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.class, StatementParameterType.STRING),
		OffsetDateTimeType("$T $L = $T.offsetDateTime(exchange,$S)", false, java.time.OffsetDateTime.class, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.class, StatementParameterType.STRING),

		FloatType("Integer $L = $T.floatValue(exchange,$S)", false, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.class, StatementParameterType.STRING),
		DoubleType("Integer $L = $T.doubleValue(exchange,$S)", false, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.class, StatementParameterType.STRING),

		ValueOfType("$T $L = $T.valueOf($T.string(exchange,$S))", false, StatementParameterType.TYPE, StatementParameterType.LITERAL, StatementParameterType.TYPE, io.sinistral.proteus.server.Extractors.class, StatementParameterType.STRING),
		FromStringType("$T $L = $T.fromString($T.string(exchange,$S))", false, StatementParameterType.TYPE, StatementParameterType.LITERAL, StatementParameterType.TYPE, io.sinistral.proteus.server.Extractors.class, StatementParameterType.STRING),

		ListValueOfType("$T<$T> $L = exchange.getQueryParameters().get($S).stream().map($T::valueOf).collect(java.util.stream.Collectors.toList())", false, java.util.List.class, StatementParameterType.RAW, StatementParameterType.LITERAL, StatementParameterType.STRING, StatementParameterType.RAW),
		ListFromStringType("$T<$T> $L = exchange.getQueryParameters().get($S).stream().map($T::fromString).collect(java.util.stream.Collectors.toList())", false, java.util.List.class, StatementParameterType.RAW, StatementParameterType.LITERAL, StatementParameterType.STRING, StatementParameterType.RAW),

		HeaderValueOfType("$T $L = $T.valueOf($T.string(exchange,$S))", false, StatementParameterType.TYPE, StatementParameterType.LITERAL, StatementParameterType.TYPE, io.sinistral.proteus.server.Extractors.Header.class, StatementParameterType.STRING),
		HeaderFromStringType("$T $L = $T.fromString($T.string(exchange,$S))", false, StatementParameterType.TYPE, StatementParameterType.LITERAL, StatementParameterType.TYPE, io.sinistral.proteus.server.Extractors.Header.class, StatementParameterType.STRING),
		HeaderStringType("$T $L = $T.string(exchange,$S)", false, java.lang.String.class, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.Header.class, StatementParameterType.STRING),

		OptionalHeaderValueOfType("$T<$T> $L = $T.string(exchange,$S).map($T::valueOf)", false, Optional.class, StatementParameterType.RAW, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.Header.Optional.class, StatementParameterType.STRING, StatementParameterType.RAW),
		OptionalHeaderFromStringType("$T<$T> $L = $T.string(exchange,$S).map($T::fromString)", false, Optional.class, StatementParameterType.RAW, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.Header.Optional.class, StatementParameterType.STRING, StatementParameterType.RAW),
		OptionalHeaderStringType("$T<$T> $L = $T.string(exchange,$S)", false, Optional.class, java.lang.String.class, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.Header.Optional.class, StatementParameterType.STRING),

		OptionalListValueOfType("$T $L = java.util.Optional.ofNullable(exchange.getQueryParameters().get($S)).map(java.util.Deque::stream).map( p -> p.map($T::valueOf).collect(java.util.stream.Collectors.toList()))", false, StatementParameterType.RAW, StatementParameterType.LITERAL, StatementParameterType.STRING, StatementParameterType.RAW),
		OptionalListFromStringType("$T $L = java.util.Optional.ofNullable(exchange.getQueryParameters().get($S)).map(java.util.Deque::stream).map( p -> p.map($T::fromString).collect(java.util.stream.Collectors.toList()))", false, StatementParameterType.RAW, StatementParameterType.LITERAL, StatementParameterType.STRING, StatementParameterType.RAW),

		OptionalJsonIteratorType("$T<$T> $L = $T.jsonIterator(exchange)", true, Optional.class, com.jsoniter.JsonIterator.class, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.Optional.class),
		OptionalAnyType("$T<$T> $L = $T.any(exchange)", true, Optional.class, com.jsoniter.any.Any.class, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.Optional.class),
		OptionalStringType("$T<String> $L = $T.string(exchange,$S)", false, Optional.class, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.Optional.class, StatementParameterType.STRING),
		OptionalLongType("$T<Long> $L = $T.longValue(exchange,$S)", false, Optional.class, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.Optional.class, StatementParameterType.STRING),
		OptionalIntegerType("$T<Integer> $L = $T.integerValue(exchange,$S)", false, Optional.class, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.Optional.class, StatementParameterType.STRING),
		OptionalBooleanType("$T<Boolean> $L = $T.booleanValue(exchange,$S)", false, Optional.class, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.Optional.class, StatementParameterType.STRING),
		OptionalFilePathType("$T<$T> $L = $T.filePath(exchange,$S)", true, Optional.class, java.nio.file.Path.class, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.Optional.class, StatementParameterType.STRING),

		OptionalByteBufferType("$T<$T> $L = $T.byteBuffer(exchange,$S)", true, Optional.class, java.nio.ByteBuffer.class, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.Optional.class, StatementParameterType.STRING),

		OptionalFloatType("$T<Long> $L = $T.floatValue(exchange,$S)", false, Optional.class, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.Optional.class, StatementParameterType.STRING),
		OptionalDoubleType("$T<Integer> $L = $T.doubleValue(exchange,$S)", false, Optional.class, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.Optional.class, StatementParameterType.STRING),

		OptionalDateType("$T<$T> $L = $T.date(exchange,$S)", false, Optional.class, java.util.Date.class, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.Optional.class, StatementParameterType.STRING),
		OptionalInstantType("$T<$T> $L = $T.instant(exchange,$S)", false, Optional.class, java.time.Instant.class, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.Optional.class, StatementParameterType.STRING),
		OptionalZonedDateTimeType("$T<$T> $L = $T.zonedDateTime(exchange,$S)", false, Optional.class, java.time.ZonedDateTime.class, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.Optional.class, StatementParameterType.STRING),
		OptionalOffsetDateTimeType("$T<$T> $L = $T.offsetDateTime(exchange,$S)", false, Optional.class, java.time.OffsetDateTime.class, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.Optional.class, StatementParameterType.STRING),

		OptionalModelType("java.util.Optional<$L> $L = $T.model(exchange,$L)", false, StatementParameterType.LITERAL, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.Optional.class, StatementParameterType.LITERAL),

		OptionalValueOfType("$T<$T> $L = $T.string(exchange,$S).map($T::valueOf)", false, Optional.class, StatementParameterType.RAW, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.Optional.class, StatementParameterType.STRING, StatementParameterType.RAW),
		OptionalFromStringType("$T<$T> $L = $T.string(exchange,$S).map($T::fromString)", false, Optional.class, StatementParameterType.RAW, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.Optional.class, StatementParameterType.STRING, StatementParameterType.RAW),

		// OptionalEnumType("$T $L = $T.enumValue(exchange,$T.class,$S)", true, StatementParameterType.TYPE, StatementParameterType.LITERAL, io.sinistral.proteus.server.Extractors.Optional.class, StatementParameterType.RAW, StatementParameterType.STRING),

		;

		public boolean isBlocking()
		{
			return this.isBlocking;
		}

		public String statement()
		{
			return this.statement;
		}

		/**
		 * The template statement for the {@link com.squareup.javapoet.MethodSpec.Builder} to use
		 */
		final private String statement;

		/**
		 * If the <code>TypeLiteral</code> requires the {@link io.undertow.server.HttpHandler} to block
		 */
		final private boolean isBlocking;

		/**
		 * An {@code Object} array that is passed to the {@code statement}
		 */
		final private Object[] parameterTypes;

		TypeHandler(String statement, boolean isBlocking, Object... types)
		{
			this.statement = statement;
			this.isBlocking = isBlocking;
			this.parameterTypes = types;
		}

		/**
		 * Helper function to bind values to a {@link com.squareup.javapoet.MethodSpec.Builder}
		 * 
		 * @param builder
		 * @param parameter
		 * @param handler
		 * @throws Exception
		 */
		public static void addStatement(MethodSpec.Builder builder, Parameter parameter, TypeHandler handler) throws Exception
		{
			Object[] args = new Object[handler.parameterTypes.length];

			for (int i = 0; i < handler.parameterTypes.length; i++)
			{
				if (handler.parameterTypes[i] instanceof StatementParameterType)
				{
					String pName = parameter.getName();

					if (parameter.isAnnotationPresent(QueryParam.class))
					{
						QueryParam qp = parameter.getAnnotation(QueryParam.class);
						pName = qp.value();
					}
					else if (parameter.isAnnotationPresent(HeaderParam.class))
					{
						HeaderParam hp = parameter.getAnnotation(HeaderParam.class);
						pName = hp.value();
					}
					else if (parameter.isAnnotationPresent(PathParam.class))
					{
						PathParam pp = parameter.getAnnotation(PathParam.class);
						pName = pp.value();
					}
					else if (parameter.isAnnotationPresent(CookieParam.class))
					{
						CookieParam cp = parameter.getAnnotation(CookieParam.class);
						pName = cp.value();
					}
					else if (parameter.isAnnotationPresent(FormParam.class))
					{
						FormParam fp = parameter.getAnnotation(FormParam.class);
						pName = fp.value();
					}

					StatementParameterType pType = (StatementParameterType) handler.parameterTypes[i];
					switch (pType)
					{
					case LITERAL:
						args[i] = parameter.getName();
						break;
					case STRING:
						args[i] = pName;
						break;
					case TYPE:
						args[i] = parameter.getParameterizedType();
						break;
					case RAW:
					{
						Type type = parameter.getParameterizedType();
						type = extractErasedType(type);
						args[i] = type;
						break;
					}
					default:
						break;
					}
				}
				else if (handler.parameterTypes[i] instanceof Class)
				{
					Class<?> clazz = (Class<?>) handler.parameterTypes[i];

					args[i] = clazz;

				}
			}

			builder.addStatement(handler.statement, args);
		}

		/**
		 * Helper function to bind a {@link Parameter} to a {@link com.squareup.javapoet.MethodSpec.Builder}
		 * 
		 * @param builder
		 * @param parameter
		 * @throws Exception
		 */
		public static void addStatement(MethodSpec.Builder builder, Parameter parameter) throws Exception
		{
			TypeHandler handler = forType(parameter.getParameterizedType());

			addStatement(builder, parameter, handler);

		}

		/**
		 * Lookup the <code>TypeHandler</code> for a {@link Type}
		 */
		public static TypeHandler forType(Type type)
		{
 
			boolean hasValueOf = false;
			boolean hasFromString = false;
			boolean isOptional = type.getTypeName().contains("java.util.Optional");
			boolean isArray = type.getTypeName().contains("java.util.List");

			if (!isOptional && !isArray)
			{
				try
				{
					Class<?> clazz = Class.forName(type.getTypeName());

					hasValueOf = hasValueOfMethod(clazz);

					hasFromString = hasFromStringMethod(clazz);

				} catch (Exception e)
				{
					log.error(e.getMessage(), e);
				}
			}

			if (isArray && !isOptional)
			{
				try
				{
					Class<?> erasedType = (Class<?>) extractErasedType(type);

					if (hasValueOfMethod(erasedType))
					{
						return ListValueOfType;

					}
					else if (hasFromStringMethod(erasedType))
					{
						return ListFromStringType;
					}
					else
					{
						return ModelType;
					}

				} catch (Exception e)
				{
					log.error(e.getMessage(), e);

				}
			}
			else if (isArray && isOptional)
			{
				try
				{

					if (type instanceof ParameterizedType)
					{
						ParameterizedType pType = (ParameterizedType) type;
						type = pType.getActualTypeArguments()[0];
					}

					Class<?> erasedType = (Class<?>) extractErasedType(type);

					if (hasValueOfMethod(erasedType))
					{
						return OptionalListValueOfType;

					}
					else if (hasFromStringMethod(erasedType))
					{
						return OptionalListFromStringType;
					}
					else
					{
						return ModelType;
					}

				} catch (Exception e)
				{
					log.error(e.getMessage(), e);

				}
			}

			// log.debug("type: " + type.getTypeName() + " valueOf: " + hasValueOf + " fromString: " + hasFromString);

			if (type.equals(Long.class))
			{
				return LongType;
			}
			else if (type.equals(Integer.class))
			{
				return IntegerType;
			}
			else if (type.equals(Float.class))
			{
				return FloatType;
			}
			else if (type.equals(Double.class))
			{
				return DoubleType;
			}
			else if (type.equals(java.nio.ByteBuffer.class))
			{
				return ByteBufferType;
			}
			else if (type.equals(Boolean.class))
			{
				return BooleanType;
			}
			else if (type.equals(String.class))
			{
				return StringType;
			}
			else if (type.equals(java.nio.file.Path.class))
			{
				return FilePathType;
			}
			else if (type.equals(java.util.Date.class))
			{
				return DateType;
			}
			else if (type.equals(java.time.ZonedDateTime.class))
			{
				return ZonedDateTimeType;
			}
			else if (type.equals(java.time.OffsetDateTime.class))
			{
				return OffsetDateTimeType;
			}
			else if (type.equals(com.jsoniter.any.Any.class))
			{
				return AnyType;
			}
			else if (type.equals(com.jsoniter.JsonIterator.class))
			{
				return JsonIteratorType;
			}
			else if (isOptional)
			{
				if (type.getTypeName().contains("java.lang.Long"))
				{
					return OptionalLongType;
				}
				else if (type.getTypeName().contains("java.lang.String"))
				{
					return OptionalStringType;
				}
				else if (type.getTypeName().contains("java.util.Date"))
				{
					return OptionalDateType;
				}
				else if (type.getTypeName().contains("java.time.OffsetDateTime"))
				{
					return OptionalOffsetDateTimeType;
				}
				else if (type.getTypeName().contains("java.time.ZonedDateTime"))
				{
					return ZonedDateTimeType;
				}
				else if (type.getTypeName().contains("java.lang.Boolean"))
				{
					return OptionalBooleanType;
				}
				else if (type.getTypeName().contains("java.lang.Float"))
				{
					return OptionalFloatType;
				}
				else if (type.getTypeName().contains("java.lang.Double"))
				{
					return OptionalDoubleType;
				}
				else if (type.getTypeName().contains("java.lang.Integer"))
				{
					return OptionalIntegerType;
				}
				else if (type.getTypeName().contains("java.nio.file.Path"))
				{
					return OptionalFilePathType;
				}
				else if (type.getTypeName().contains("java.nio.ByteBuffer"))
				{
					return OptionalByteBufferType;
				}
				else
				{
					try
					{

						Class<?> erasedType = (Class<?>) extractErasedType(type);

 
						if (hasValueOfMethod(erasedType))
						{
							return OptionalValueOfType;

						}
						else if (hasFromStringMethod(erasedType))
						{
							return OptionalFromStringType;

						}

					} catch (Exception e)
					{
						log.error("error : " + e.getMessage(), e);
						return OptionalStringType;
					}

					return OptionalStringType;
				}
			}

			else if (hasValueOf)
			{
				return ValueOfType;
			}
			else if (hasFromString)
			{
				return FromStringType;
			}
			else
			{
				return ModelType;
			}
		}
	}

	@Inject
	@Named("application.path")
	protected String applicationPath;

	protected String packageName;
	protected String className;
	protected String sourceString;

	@Inject
	@Named("registeredEndpoints")
	protected Set<EndpointInfo> registeredEndpoints;

	protected Class<?> controllerClass;

	/**
	 * Create a new {@code HandlerGenerator} instance used to generate a {@code Supplier<RoutingHandler>} class
	 * 
	 * @param packageName
	 *            generated class package name
	 * @param controllerClass
	 *            the class handlers will be generated from this class
	 */
	public HandlerGenerator(String packageName, Class<?> controllerClass)
	{
		this.packageName = packageName;
		this.controllerClass = controllerClass;
		this.className = controllerClass.getSimpleName() + "RouteSupplier";
	}

	/**
	 * Compiles the generated source into a new {@link Class}
	 * 
	 * @return a new {@code Supplier<RoutingHandler>} class
	 */
	public Class<? extends Supplier<RoutingHandler>> compileClass()
	{
		try
		{
			this.generateRoutes();

			log.debug("\n\nGenerated Class Source:\n\n" + this.sourceString);

			return CompilerUtils.CACHED_COMPILER.loadFromJava(packageName + "." + className, this.sourceString);

		} catch (Exception e)
		{
			log.error(e.getMessage(), e);
			return null;
		}
	}

	/**
	 * Generates the routing Java source code
	 */
	protected void generateRoutes()
	{
		try
		{

			TypeSpec.Builder typeBuilder = TypeSpec.classBuilder(className).addModifiers(Modifier.PUBLIC).addSuperinterface(ParameterizedTypeName.get(Supplier.class, RoutingHandler.class));

			ClassName extractorClass = ClassName.get("io.sinistral.proteus.server", "Extractors");

			ClassName injectClass = ClassName.get("com.google.inject", "Inject");
			
 
			MethodSpec.Builder constructor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addAnnotation(injectClass);

			String className = this.controllerClass.getSimpleName().toLowerCase() + "Controller";

			typeBuilder.addField(this.controllerClass, className, Modifier.PROTECTED,  Modifier.FINAL);
			
			ClassName wrapperClass = ClassName.get("io.undertow.server", "HandlerWrapper");
			ClassName stringClass = ClassName.get("java.lang", "String"); 
			ClassName mapClass = ClassName.get("java.util", "Map");
			TypeName mapOfWrappers = ParameterizedTypeName.get(mapClass, stringClass, wrapperClass);
			
			TypeName annotatedMapOfWrappers = mapOfWrappers.annotated(AnnotationSpec.builder(com.google.inject.name.Named.class).addMember("value", "$S","registeredHandlerWrappers").build());
			
			typeBuilder.addField(mapOfWrappers, "registeredHandlerWrappers", Modifier.PROTECTED,  Modifier.FINAL); 

			constructor.addParameter(this.controllerClass, className);
			constructor.addParameter(annotatedMapOfWrappers, "registeredHandlerWrappers");
			constructor.addStatement("this.$N = $N", className, className);
			constructor.addStatement("this.$N = $N", "registeredHandlerWrappers", "registeredHandlerWrappers");

			addClassMethodHandlers(typeBuilder, this.controllerClass);

			typeBuilder.addMethod(constructor.build());

			JavaFile javaFile = JavaFile.builder(packageName, typeBuilder.build()).addStaticImport(extractorClass, "*").build();

			StringBuilder sb = new StringBuilder();

			javaFile.writeTo(sb);

			this.sourceString = sb.toString();

		} catch (Exception e)
		{
			log.error(e.getMessage(), e);
		}
	}

	protected void addClassMethodHandlers(TypeSpec.Builder typeBuilder, Class<?> clazz) throws Exception
	{
		ClassName httpHandlerClass = ClassName.get("io.undertow.server", "HttpHandler");

		String controllerName = clazz.getSimpleName().toLowerCase() + "Controller";
		
		HashSet<String> handlerNameSet = new HashSet<>();

		MethodSpec.Builder initBuilder = MethodSpec.methodBuilder("get").addModifiers(Modifier.PUBLIC).returns(RoutingHandler.class).addStatement("final $T router = new $T()", io.undertow.server.RoutingHandler.class, io.undertow.server.RoutingHandler.class);

		final Map<Type, String> parameterizedLiteralsNameMap = Arrays.stream(clazz.getDeclaredMethods()).flatMap(m -> Arrays.stream(m.getParameters()).map(Parameter::getParameterizedType).filter(t -> t.getTypeName().contains("<") && !t.getTypeName().contains("concurrent")))
				.distinct().filter(t -> {
					TypeHandler handler = TypeHandler.forType(t);
					return (handler.equals(TypeHandler.ModelType) || handler.equals(TypeHandler.OptionalModelType));
				}).collect(Collectors.toMap(java.util.function.Function.identity(), HandlerGenerator::typeLiteralNameForParameterizedType));

		final Map<Type, String> literalsNameMap = Arrays.stream(clazz.getDeclaredMethods()).flatMap(m -> Arrays.stream(m.getParameters()).map(Parameter::getParameterizedType)).filter(t -> {

			if (t.getTypeName().contains("java.util"))
			{
				return false;
			}

			try
			{
				Class<?> optionalType = (Class<?>) extractErasedType(t);

				if (optionalType != null)
				{
					t = optionalType;
				}

			} catch (Exception e)
			{

			}

			if (t.getTypeName().contains("java.lang"))
			{
				return false;
			}
			else if (t.getTypeName().contains("java.nio"))
			{
				return false;
			}
			else if (t.getTypeName().contains("java.io"))
			{
				return false;
			}
			else if (t.getTypeName().contains("java.util"))
			{
				return false;
			}
			else if (t.equals(HttpServerExchange.class) || t.equals(ServerRequest.class))
			{
				return false;
			}

			if (t instanceof Class)
			{
				Class<?> pClazz = (Class<?>) t;
				if (pClazz.isPrimitive())
				{
					return false;
				}
				if (pClazz.isEnum())
				{
					return false;
				}

			}

			return true;

		}).distinct().collect(Collectors.toMap(java.util.function.Function.identity(), HandlerGenerator::typeLiteralNameForType));

		parameterizedLiteralsNameMap.forEach((t, n) -> initBuilder.addStatement("final $T<$L> $LTypeLiteral = new $T<$L>(){}", TypeLiteral.class, t, n, TypeLiteral.class, t));

		literalsNameMap.forEach((t, n) -> initBuilder.addStatement("final $T<$T> $LTypeLiteral = new $T<$T>(){}", TypeLiteral.class, t, n, TypeLiteral.class, t));

		Optional<io.sinistral.proteus.annotations.Chain> typeLevelWrapAnnotation = Optional.ofNullable(clazz.getAnnotation(io.sinistral.proteus.annotations.Chain.class));
		Map<Class<? extends HandlerWrapper>, String> typeLevelHandlerWrapperMap = new LinkedHashMap<Class<? extends HandlerWrapper>, String>();

		if (typeLevelWrapAnnotation.isPresent())
		{
			io.sinistral.proteus.annotations.Chain w = typeLevelWrapAnnotation.get();

			Class<? extends HandlerWrapper> wrapperClasses[] = w.value();

			for (int i = 0; i < wrapperClasses.length; i++)
			{
				Class<? extends HandlerWrapper> wrapperClass = wrapperClasses[i];

				String wrapperName = generateFieldName(wrapperClass.getCanonicalName());

				initBuilder.addStatement("final $T $L = new $T()", wrapperClass, wrapperName, wrapperClass);

				typeLevelHandlerWrapperMap.put(wrapperClass, wrapperName);
			}
		}
		
		

		initBuilder.addStatement("$T currentHandler = $L", HttpHandler.class, "null");

		initBuilder.addCode("$L", "\n");

		List<String> consumesContentTypes = new ArrayList<>();
		List<String> producesContentTypes = new ArrayList<>();
		
		/*
		 * Controller Level Authorization
		 */
		
		List<String> typeLevelSecurityDefinitions = new ArrayList<>();
		
		if( Optional.ofNullable(clazz.getAnnotation(io.swagger.annotations.Api.class)).isPresent() )
		{
			io.swagger.annotations.Api apiAnnotation = clazz.getAnnotation(io.swagger.annotations.Api.class);
			
			io.swagger.annotations.Authorization[] authorizationAnnotations = apiAnnotation.authorizations();
			
			if(authorizationAnnotations.length > 0)
			{
				for(io.swagger.annotations.Authorization authorizationAnnotation: authorizationAnnotations )
				{
					if(authorizationAnnotation.value().length() > 0)
					{
						typeLevelSecurityDefinitions.add(authorizationAnnotation.value());
					}
				}
			} 
		}

		for (Method m : clazz.getDeclaredMethods())
		{

			if (!Optional.ofNullable(m.getAnnotation(javax.ws.rs.Path.class)).isPresent())
			{
				continue;
			}

			EndpointInfo endpointInfo = new EndpointInfo();

			String producesContentType = "*/*";
			String consumesContentType = "*/*";

			Boolean isBlocking = false;
			
			Optional<Blocking> blockingAnnotation = Optional.ofNullable(m.getAnnotation(Blocking.class));
			
			if(blockingAnnotation.isPresent())
			{
				isBlocking = blockingAnnotation.get().value();
			}

			
			Optional<javax.ws.rs.Produces> producesAnnotation = Optional.ofNullable(m.getAnnotation(javax.ws.rs.Produces.class));

			if (!producesAnnotation.isPresent())
			{
				producesAnnotation = Optional.ofNullable(clazz.getAnnotation(javax.ws.rs.Produces.class));

				if (producesAnnotation.isPresent())
				{

					producesContentTypes = Arrays.stream(producesAnnotation.get().value()).flatMap(v -> Arrays.stream((v.split(",")))).collect(Collectors.toList());

					producesContentType = producesContentTypes.stream().collect(Collectors.joining(","));
				}

			}
			else
			{
				producesContentTypes = Arrays.stream(producesAnnotation.get().value()).flatMap(v -> Arrays.stream((v.split(",")))).collect(Collectors.toList());

				producesContentType = producesContentTypes.stream().collect(Collectors.joining(","));
			}

			endpointInfo.setProduces(producesContentType);

			Optional<javax.ws.rs.Consumes> consumesAnnotation = Optional.ofNullable(m.getAnnotation(javax.ws.rs.Consumes.class));

			if (!consumesAnnotation.isPresent())
			{
				consumesAnnotation = Optional.ofNullable(clazz.getAnnotation(javax.ws.rs.Consumes.class));

				if (consumesAnnotation.isPresent())
				{
					consumesContentTypes = Arrays.stream(consumesAnnotation.get().value()).flatMap(v -> Arrays.stream((v.split(",")))).collect(Collectors.toList());

					consumesContentType = consumesContentTypes.stream().collect(Collectors.joining(","));
				}
			}
			else
			{
				consumesContentTypes = Arrays.stream(consumesAnnotation.get().value()).flatMap(v -> Arrays.stream((v.split(",")))).collect(Collectors.toList());

				consumesContentType = consumesContentTypes.stream().collect(Collectors.joining(","));
			}

			endpointInfo.setControllerName(clazz.getSimpleName());
			
		
			String methodPath = null;

			try
			{
				methodPath = Extractors.pathTemplateFromMethod.apply(m).replaceAll("\\/\\/", "\\/");
			} catch (Exception e)
			{
				log.error(e.getMessage() + " for " + m, e);
				continue;
			}

			methodPath = applicationPath + methodPath;

			HttpString httpMethod = Extractors.httpMethodFromMethod.apply(m);

			endpointInfo.setMethod(httpMethod);

			endpointInfo.setConsumes(consumesContentType);

			endpointInfo.setPathTemplate(methodPath);

			endpointInfo.setControllerMethod(m.getName());

			String handlerName = String.format("%c%s%sHandler", Character.toLowerCase(clazz.getSimpleName().charAt(0)), clazz.getSimpleName().substring(1, clazz.getSimpleName().length()), StringUtils.capitalize(m.getName()));
			
			int nameIndex = 1;
			
			while(handlerNameSet.contains(handlerName))
			{
				handlerName = handlerName + "_" + nameIndex++;
			}
			
			handlerNameSet.add(handlerName);

			TypeSpec.Builder handlerClassBuilder = TypeSpec.anonymousClassBuilder("").addSuperinterface(httpHandlerClass);

			// handlerClassBuilder.addModifiers(Modifier.PUBLIC);

			MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder("handleRequest").addModifiers(Modifier.PUBLIC).addException(ClassName.get("java.lang", "Exception")).addAnnotation(Override.class)
					.addParameter(ParameterSpec.builder(HttpServerExchange.class, "exchange", Modifier.FINAL).build());

			for (Parameter p : m.getParameters())
			{

				if (p.getParameterizedType().equals(ServerRequest.class) || p.getParameterizedType().equals(HttpServerExchange.class) || p.getParameterizedType().equals(HttpHandler.class))
				{
					continue;
				}

				try
				{
					TypeHandler t = TypeHandler.forType(p.getParameterizedType());

					if (t.isBlocking())
					{
						isBlocking = true;
						
//						methodBuilder.beginControlFlow("if(exchange.isInIoThread())");
//						methodBuilder.addStatement("exchange.dispatch(this)");
//						methodBuilder.addStatement("return");
//
//						methodBuilder.endControlFlow();

						break;
					}

				} catch (Exception e)
				{
					log.error(e.getMessage(), e);
				}
			}

			Arrays.stream(m.getParameters()).forEachOrdered(p -> {

				Type type = p.getParameterizedType();

				try
				{

					if (p.getType().equals(ServerRequest.class))
					{
						methodBuilder.addStatement("$T $L = new $T(exchange)", ServerRequest.class, p.getName(), ServerRequest.class);

					}
					else if (p.getType().equals(HttpServerExchange.class))
					{
						// methodBuilder.addCode("$L", "\n");
					}
					else if (p.getType().equals(HttpHandler.class))
					{
						methodBuilder.addStatement("$T $L = this", HttpHandler.class, p.getName());
						// methodBuilder.addCode("$L", "\n");
					}
					else
					{
						if (p.isAnnotationPresent(HeaderParam.class))
						{

							TypeHandler handler = TypeHandler.forType(type);

							if (handler.equals(TypeHandler.OptionalStringType))
							{
								handler = TypeHandler.OptionalHeaderStringType;

								TypeHandler.addStatement(methodBuilder, p, handler);

							}
							else if (handler.equals(TypeHandler.OptionalValueOfType))
							{
								handler = TypeHandler.OptionalHeaderValueOfType;

								TypeHandler.addStatement(methodBuilder, p, handler);

							}
							else if (handler.equals(TypeHandler.OptionalFromStringType))
							{
								handler = TypeHandler.OptionalHeaderFromStringType;
								TypeHandler.addStatement(methodBuilder, p, handler);

							}
							else if (handler.equals(TypeHandler.StringType))
							{
								handler = TypeHandler.HeaderStringType;
								TypeHandler.addStatement(methodBuilder, p, handler);

							}
							else if (handler.equals(TypeHandler.ValueOfType))
							{
								handler = TypeHandler.HeaderValueOfType;
								TypeHandler.addStatement(methodBuilder, p, handler);

							}
							else if (handler.equals(TypeHandler.FromStringType))
							{
								handler = TypeHandler.HeaderFromStringType;
								TypeHandler.addStatement(methodBuilder, p, handler);

							}

							else
							{
								handler = TypeHandler.HeaderStringType;

								TypeHandler.addStatement(methodBuilder, p, handler);

							}

						}
						else
						{
							TypeHandler t = TypeHandler.forType(type);

							if (t.equals(TypeHandler.OptionalModelType) || t.equals(TypeHandler.ModelType))
							{
								String interfaceType = parameterizedLiteralsNameMap.get(type);

								String pType = interfaceType != null ? interfaceType + "TypeLiteral" : type.getTypeName() + ".class";

								methodBuilder.addStatement(t.statement, type, p.getName(), pType);

							}
							else if (t.equals(TypeHandler.OptionalFromStringType) || t.equals(TypeHandler.OptionalValueOfType))
							{

								TypeHandler.addStatement(methodBuilder, p);
							}
							else if (t.equals(TypeHandler.OptionalListFromStringType) || t.equals(TypeHandler.OptionalListValueOfType))
							{
								// $T $L = java.util.Optional.ofNullable(exchange.getQueryParameters().get($S)).map(java.util.Deque::stream).map($T

								ParameterizedType pType = (ParameterizedType) type;

								if (type instanceof ParameterizedType)
								{
									pType = (ParameterizedType) type;
									type = pType.getActualTypeArguments()[0];
								}

								Class<?> erasedType = (Class<?>) extractErasedType(type);

								methodBuilder.addStatement(t.statement, pType, p.getName(), p.getName(), erasedType);

							}
							else
							{
								TypeHandler.addStatement(methodBuilder, p);
							}
						}
					}

				} catch (Exception e)
				{
					log.error(e.getMessage(), e);
				}

			});

			methodBuilder.addCode("$L", "\n");

			CodeBlock.Builder functionBlockBuilder = CodeBlock.builder();

			String controllerMethodArgs = Arrays.stream(m.getParameters()).map(Parameter::getName).collect(Collectors.joining(","));

			if (!m.getReturnType().toString().equals("void"))
			{
				if (m.getReturnType().getTypeName().contains("java.util.concurrent.CompletionStage") || m.getReturnType().getTypeName().contains("java.util.concurrent.CompletableFuture"))
				{
					Type futureType = m.getGenericReturnType();

					functionBlockBuilder.add("$T $L = $L.$L($L);", futureType, "response", controllerName, m.getName(), controllerMethodArgs);

				}
				else
				{
					functionBlockBuilder.add("$T $L = $L.$L($L);", m.getGenericReturnType(), "response", controllerName, m.getName(), controllerMethodArgs);
				}

				methodBuilder.addCode(functionBlockBuilder.build());

				methodBuilder.addCode("$L", "\n");

				if (m.getReturnType().equals(ServerResponse.class))
				{
					methodBuilder.addStatement("$L.send(this,$L)", "response", "exchange");

				}
				else if (m.getReturnType().getTypeName().contains("java.util.concurrent.CompletionStage") || m.getReturnType().getTypeName().contains("java.util.concurrent.CompletableFuture"))
				{
					String postProcess = ".";

					if (!producesContentType.contains(","))
					{
						if (producesContentType.contains(MediaType.APPLICATION_JSON))
						{
							postProcess = ".applicationJson().";
						}
						else if (producesContentType.contains(MediaType.APPLICATION_XML))
						{
							postProcess = ".applicationXml().";
						}
						else if (producesContentType.contains(MediaType.TEXT_HTML))
						{
							postProcess = ".textHtml().";
						}
					}

					methodBuilder.addCode("$L.thenAcceptAsync( r ->  r" + postProcess + "send(this,$L), io.undertow.util.SameThreadExecutor.INSTANCE )\n\t.exceptionally( ex -> ", "response", "exchange");
					methodBuilder.beginControlFlow("", "");
					methodBuilder.addCode("\t\tthrow new java.util.concurrent.CompletionException(ex);\n\t");
					methodBuilder.endControlFlow(")", "");
				}
				else
				{

					methodBuilder.addStatement("exchange.getResponseHeaders().put($T.CONTENT_TYPE, $S)", Headers.class, producesContentType);

					if (m.getReturnType().equals(String.class))
					{
						methodBuilder.addStatement("exchange.getResponseHeaders().send($L)", "response");
					}
					else
					{
						methodBuilder.addStatement("exchange.getResponseSender().send(com.jsoniter.output.JsonStream.serialize($L))", "response");
					}

				}

				handlerClassBuilder.addMethod(methodBuilder.build());

			}
			else
			{

				functionBlockBuilder.add("$L.$L($L);", controllerName, m.getName(), controllerMethodArgs);

				methodBuilder.addCode(functionBlockBuilder.build());

				methodBuilder.addCode("$L", "\n");

				handlerClassBuilder.addMethod(methodBuilder.build());

			}

			FieldSpec handlerField = FieldSpec.builder(httpHandlerClass, handlerName, Modifier.FINAL).initializer("$L", handlerClassBuilder.build()).build();

			initBuilder.addCode("$L\n", handlerField.toString());

			Optional<io.sinistral.proteus.annotations.Chain> wrapAnnotation = Optional.ofNullable(m.getAnnotation(io.sinistral.proteus.annotations.Chain.class));

			/*
			 * Authorization
			 */
			
			List<String> securityDefinitions = new ArrayList<>();
			
			/*
			 * @TODO wrap blocking in BlockingHandler
			 */
			
 			
			if( Optional.ofNullable(m.getAnnotation(io.swagger.annotations.ApiOperation.class)).isPresent() )
			{
				io.swagger.annotations.ApiOperation apiOperationAnnotation = m.getAnnotation(io.swagger.annotations.ApiOperation.class);
				
				io.swagger.annotations.Authorization[] authorizationAnnotations = apiOperationAnnotation.authorizations();
				if(authorizationAnnotations.length > 0)
				{
					for(io.swagger.annotations.Authorization authorizationAnnotation: authorizationAnnotations )
					{
						if(authorizationAnnotation.value().length() > 0)
						{
							securityDefinitions.add(authorizationAnnotation.value());
						}
					}
				} 
			}
			
			if(securityDefinitions.isEmpty())
			{
				securityDefinitions.addAll(typeLevelSecurityDefinitions);
			}

			if(isBlocking)
			{
				handlerName = "new io.undertow.server.handlers.BlockingHandler(" + handlerName + ")";
			}
			
			if (wrapAnnotation.isPresent() || typeLevelHandlerWrapperMap.size() > 0 || securityDefinitions.size() > 0)
			{
				initBuilder.addStatement("currentHandler = $L", handlerName);

				if (wrapAnnotation.isPresent())
				{
					io.sinistral.proteus.annotations.Chain w = wrapAnnotation.get();

					Class<? extends HandlerWrapper> wrapperClasses[] = w.value();

					for (int i = 0; i < wrapperClasses.length; i++)
					{
						Class<? extends HandlerWrapper> wrapperClass = wrapperClasses[i];

						String wrapperName = typeLevelHandlerWrapperMap.get(wrapperClass);

						if (wrapperName == null)
						{
							wrapperName = generateFieldName(wrapperClass.getCanonicalName());

							initBuilder.addStatement("final $T $L = new $T()", wrapperClass, wrapperName, wrapperClass);
						}

						initBuilder.addStatement("currentHandler = $L.wrap($L)", wrapperName, "currentHandler"); 
					}
				}
				 
				for (Class<? extends HandlerWrapper> wrapperClass : typeLevelHandlerWrapperMap.keySet())
				{
					String wrapperName = typeLevelHandlerWrapperMap.get(wrapperClass);
					initBuilder.addStatement("currentHandler = $L.wrap($L)", wrapperName, "currentHandler");
				} 
				
				for (String securityDefinitionName : securityDefinitions)
				{
					initBuilder.addStatement("currentHandler = registeredHandlerWrappers.get($S).wrap($L)", securityDefinitionName, "currentHandler");
				} 

				initBuilder.addStatement("$L.add(io.undertow.util.Methods.$L,$S,$L)", "router", httpMethod, methodPath, "currentHandler");
			}
			else
			{
				initBuilder.addStatement("$L.add(io.undertow.util.Methods.$L,$S,$L)", "router", httpMethod, methodPath, handlerName);
			}

			initBuilder.addCode("$L", "\n");

			registeredEndpoints.add(endpointInfo);

		}

		initBuilder.addCode("$Lreturn router;\n", "\n");

		typeBuilder.addMethod(initBuilder.build());

	}

	/**
	 * @return the packageName
	 */
	public String getPackageName()
	{
		return packageName;
	}

	/**
	 * @param packageName
	 *            the packageName to set
	 */
	public void setPackageName(String packageName)
	{
		this.packageName = packageName;
	}

	/**
	 * @return the className
	 */
	public String getClassName()
	{
		return className;
	}

	/**
	 * @param className
	 *            the className to set
	 */
	public void setClassName(String className)
	{
		this.className = className;
	}

	protected static ArrayList<String> getClassNamesFromPackage(String packageName) throws Exception
	{
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		URL packageURL;
		ArrayList<String> names = new ArrayList<>();

		packageName = packageName.replace(".", "/");
		packageURL = classLoader.getResource(packageName);

		assert packageURL != null;
		URI uri = new URI(packageURL.toString());
		File folder = new File(uri.getPath());
		// won't work with path which contains blank (%20)
		// File folder = new File(packageURL.getFile());
		File[] contenuti = folder.listFiles();
		String entryName;
		assert contenuti != null;
		for (File actual : contenuti)
		{
			if (actual.isDirectory())
			{
				continue;
			}

			entryName = actual.getName();
			entryName = entryName.substring(0, entryName.lastIndexOf('.'));
			names.add(entryName);
		}

		return names;
	}

	protected static Set<Class<?>> getApiClasses(String basePath, Predicate<String> pathPredicate) throws Exception
	{

		Reflections ref = new Reflections(basePath);
		Stream<Class<?>> stream = ref.getTypesAnnotatedWith(Api.class).stream();

		if (pathPredicate != null)
		{
			stream = stream.filter(clazz -> {

				Path annotation = clazz.getDeclaredAnnotation(Path.class);

				return annotation != null && pathPredicate.test(annotation.value());

			});
		}

		return stream.collect(Collectors.toSet());

	}

	protected static Type extractErasedType(Type type) throws Exception
	{
		String typeName = type.getTypeName();

		Matcher matcher = TYPE_NAME_PATTERN.matcher(typeName);

		if (matcher.find())
		{

			int matches = matcher.groupCount();

			if (matches == 2)
			{

				String erasedType = matcher.group(2);

				String clearDollarType = erasedType.replaceAll("\\$", ".");

				try
				{
					Class<?> clazz = Class.forName(clearDollarType);
					return clazz;

				} catch (Exception e1)
				{
					try
					{
						Class<?> clazz = Class.forName(erasedType);

						return clazz;

					} catch (Exception e2)
					{
						return type;
					}
				}

			}
			else if (matches > 2)
			{

				String erasedType = matcher.group(3);

				String clearDollarType = erasedType.replaceAll("\\$", ".");

				try
				{
					Class<?> clazz = Class.forName(clearDollarType);
					return clazz;

				} catch (Exception e1)
				{
					try
					{
						Class<?> clazz = Class.forName(erasedType);

						return clazz;

					} catch (Exception e2)
					{
						return type;
					}
				}

			}

		}
		else
		{
			// log.warn("No type found for " + typeName);
		}

		return null;
	}

	protected static String typeLiteralNameForParameterizedType(Type type)
	{
		String typeName = type.getTypeName();

		Matcher matcher = TYPE_NAME_PATTERN.matcher(typeName);

		if (matcher.find())
		{

			int matches = matcher.groupCount();

			if (matches == 2)
			{
				String genericInterface = matcher.group(1);
				String erasedType = matcher.group(2).replaceAll("\\$", ".");

				// log.debug("genericInterface: " + genericInterface);
				// log.debug("erasedType: " + erasedType);

				String[] genericParts = genericInterface.split("\\.");
				String[] erasedParts = erasedType.split("\\.");

				String genericTypeName = genericParts[genericParts.length - 1];
				String erasedTypeName;

				if (erasedParts.length > 1)
				{
					erasedTypeName = erasedParts[erasedParts.length - 2] + erasedParts[erasedParts.length - 1];
				}
				else
				{
					erasedTypeName = erasedParts[0];
				}

				typeName = String.format("%s%s%s", Character.toLowerCase(erasedTypeName.charAt(0)), erasedTypeName.substring(1, erasedTypeName.length()), genericTypeName);

				return typeName;
			}

		}

		matcher = CONCURRENT_TYPE_NAME_PATTERN.matcher(typeName);

		if (matcher.find())
		{

			int matches = matcher.groupCount();

			if (matches == 2)
			{
				String genericInterface = matcher.group(1);
				String erasedType = matcher.group(2).replaceAll("\\$", ".");

				// log.debug("genericInterface: " + genericInterface);
				// log.debug("erasedType: " + erasedType);

				String[] genericParts = genericInterface.split("\\.");
				String[] erasedParts = erasedType.split("\\.");

				String genericTypeName = genericParts[genericParts.length - 1];
				String erasedTypeName;

				if (erasedParts.length > 1)
				{
					erasedTypeName = erasedParts[erasedParts.length - 2] + erasedParts[erasedParts.length - 1];
				}
				else
				{
					erasedTypeName = erasedParts[0];
				}

				typeName = String.format("%s%s%s", Character.toLowerCase(erasedTypeName.charAt(0)), erasedTypeName.substring(1, erasedTypeName.length()), genericTypeName);
				return typeName;
			}

		}

		return typeName;
	}

	protected static String typeLiteralNameForType(Type type)
	{
		String typeName = type.getTypeName();

		String[] erasedParts = typeName.split("\\.");

		String erasedTypeName;

		if (erasedParts.length > 1)
		{
			erasedTypeName = erasedParts[erasedParts.length - 2] + erasedParts[erasedParts.length - 1];
		}
		else
		{
			erasedTypeName = erasedParts[0];
		}

		typeName = generateFieldName(erasedTypeName);

		return typeName;
	}

	protected static String generateFieldName(String name)
	{
		String[] parts = name.split("\\.");

		StringBuilder sb = new StringBuilder();

		for (int i = 0; i < parts.length; i++)
		{
			String part = parts[i];

			if (i == 0)
			{
				sb.append(String.format("%s%s", Character.toLowerCase(part.charAt(0)), part.substring(1, part.length())));
			}
			else
			{
				sb.append(String.format("%s%s", Character.toUpperCase(part.charAt(0)), part.substring(1, part.length())));
			}
		}

		return sb.toString();
	}

	protected static void generateTypeLiteral(MethodSpec.Builder builder, Type type, String name)
	{

		builder.addCode(CodeBlock.of("\n\ncom.jsoniter.spi.TypeLiteral<$T> $L = new com.jsoniter.spi.TypeLiteral<$L>(){};\n\n", type, name, type));

	}

	protected static void generateParameterReference(MethodSpec.Builder builder, Class<?> clazz)
	{

		builder.addCode(CodeBlock.of("\n\nType $LType = $T.", clazz, clazz));

	}

	protected static boolean hasValueOfMethod(Class<?> clazz)
	{
		return Arrays.stream(clazz.getMethods()).filter(m -> m.getName().equals("valueOf")).findFirst().isPresent();
	}

	protected static boolean hasFromStringMethod(Class<?> clazz)
	{
		return Arrays.stream(clazz.getMethods()).filter(m -> m.getName().equals("fromString")).findFirst().isPresent();
	}

}
