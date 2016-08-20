package org.fluentlenium.core.inject;

import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import org.fluentlenium.core.FluentContainer;
import org.fluentlenium.core.FluentControl;
import org.fluentlenium.core.annotation.AjaxElement;
import org.fluentlenium.core.annotation.Page;
import org.fluentlenium.core.components.ComponentsManager;
import org.fluentlenium.core.domain.FluentList;
import org.fluentlenium.core.domain.FluentListImpl;
import org.fluentlenium.core.domain.FluentWebElement;
import org.fluentlenium.core.events.ContainerAnnotationsEventsRegistry;
import org.fluentlenium.utils.ReflectionUtils;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.internal.Locatable;
import org.openqa.selenium.support.PageFactory;
import org.openqa.selenium.support.events.EventFiringWebDriver;
import org.openqa.selenium.support.pagefactory.AjaxElementLocatorFactory;
import org.openqa.selenium.support.pagefactory.DefaultElementLocatorFactory;
import org.openqa.selenium.support.pagefactory.ElementLocator;
import org.openqa.selenium.support.pagefactory.ElementLocatorFactory;
import org.openqa.selenium.support.pagefactory.internal.LocatingElementHandler;

import javax.inject.Inject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Handle injection of @AjaxElement proxies, @Inject objects and @FindBy.
 */
public class FluentInjector implements FluentInjectControl {

    private final ConcurrentMap<Class, Object> pageInstances = new ConcurrentHashMap<>();
    private final ConcurrentMap<Object, ContainerAnnotationsEventsRegistry> eventsContainerSupport = new ConcurrentHashMap<>();

    private final FluentControl fluentControl;
    private final ComponentsManager componentsManager;

    public FluentInjector(FluentControl fluentControl, ComponentsManager componentsManager) {
        this.fluentControl = fluentControl;
        this.componentsManager = componentsManager;
    }

    /**
     * Release all loaded containers.
     */
    public void release() {
        pageInstances.clear();
        for (ContainerAnnotationsEventsRegistry support : eventsContainerSupport.values()) {
            support.close();
        }
        eventsContainerSupport.clear();
    }

    public <T> T createPage(Class<T> cls, Object... params) {
        return newInstance(cls, params);
    }

    @Override
    public <T> T newInstance(Class<T> cls, Object... params) {
        T container = newPage(cls, params);
        inject(container);
        return container;
    }

    @Override
    public void inject(Object... containers) {
        for (Object container : containers) {
            inject(container);
        }
    }

    @Override
    public void inject(Object container) {
        initContainer(container);
        initChildrenContainers(container);
        initFluentElements(container);

        // Default Selenium WebElement injection.
        PageFactory.initElements(fluentControl.getDriver(), container);
    }

    private void initContainer(Object container) {
        if (container instanceof FluentContainer) {
            ((FluentContainer) container).initFluent(fluentControl);
        }
        initEventAnnotations(container);
    }

    private void initEventAnnotations(final Object container) {
        if (fluentControl.getDriver() instanceof EventFiringWebDriver) {
            eventsContainerSupport.put(container, new ContainerAnnotationsEventsRegistry((EventFiringWebDriver) fluentControl.getDriver(), container));
        }
    }

    private static boolean isContainer(Field field) {
        return field.isAnnotationPresent(Page.class) ||
                field.isAnnotationPresent(Inject.class);
    }

    private static boolean isClassSupported(Class<?> cls) {
        return cls != Object.class && cls != null;
    }

    private void initChildrenContainers(Object container) {
        for (Class cls = container.getClass(); isClassSupported(cls); cls = cls.getSuperclass()) {
            for (Field field : cls.getDeclaredFields()) {
                if (isContainer(field)) {
                    Class fieldClass = field.getType();
                    Object existingChildContainer = pageInstances.get(fieldClass);
                    if (existingChildContainer != null) {
                        try {
                            ReflectionUtils.set(field, container, existingChildContainer);
                        } catch (IllegalAccessException e) {
                            throw new FluentInjectException("Can't set field " + field + " with value " + existingChildContainer, e);
                        }
                    } else {
                        Object childContainer = newPage(fieldClass);
                        try {
                            ReflectionUtils.set(field, container, childContainer);
                        } catch (IllegalAccessException e) {
                            throw new FluentInjectException("Can't set field " + field + " with value " + childContainer, e);
                        }
                        pageInstances.putIfAbsent(fieldClass, childContainer);
                        inject(childContainer);
                    }
                }
            }
        }
    }

    private <T> T newPage(Class<T> cls, Object... params) {
        try {
            T page = constructContainerWithParams(cls, params);
            initContainer(page);
            return page;
        } catch (IllegalAccessException e) {
            throw new FluentInjectException("IllegalAccessException on class " + (cls != null ? cls.getName() : " null"), e);
        } catch (NoSuchMethodException e) {
            throw new FluentInjectException("No constructor found on class " + (cls != null ? cls.getName() : " null"), e);
        } catch (InstantiationException e) {
            throw new FluentInjectException("Unable to instantiate " + (cls != null ? cls.getName() : " null"), e);
        } catch (InvocationTargetException e) {
            throw new FluentInjectException("Cannot build object for class " + (cls != null ? cls.getName() : " null"), e);
        }
    }

    private <T extends FluentControl> void initFluentElements(Object container) {
        for (Class cls = container.getClass(); isClassSupported(cls); cls = cls.getSuperclass()) {
            for (Field fieldFromPage : cls.getDeclaredFields()) {
                if (isSupported(container, fieldFromPage)) {
                    AjaxElement elem = fieldFromPage.getAnnotation(AjaxElement.class);
                    if (elem == null) {
                        initFieldElements(
                                new DefaultElementLocatorFactory(this.fluentControl.getDriver()),
                                container,
                                fieldFromPage);
                    } else {
                        initFieldElements(
                                new AjaxElementLocatorFactory(this.fluentControl.getDriver(), elem.timeOutInSeconds()),
                                container,
                                fieldFromPage);
                    }
                }
            }
        }
    }

    private boolean isSupported(Object container, Field field) {
        return isValueNull(container, field) && !Modifier.isFinal(field.getModifiers()) && (isListOfFluentWebElement(field) || isList(field) || isComponent(field));
    }

    private static boolean isValueNull(Object container, Field field) {
        try {
            return ReflectionUtils.get(field, container) == null;
        } catch (IllegalAccessException e) {
            throw new FluentInjectException("Can't retrieve default value of field", e);
        }
    }

    private boolean isComponent(Field field) {
        return componentsManager.isComponentClass(field.getType());
    }

    private static boolean isListOfFluentWebElement(Field field) {
        if (isList(field)) {
            Class<?> genericType = getFirstGenericType(field);
            if (FluentWebElement.class.isAssignableFrom(genericType)) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> getFirstGenericType(Field field) {
        Type[] actualTypeArguments = ((ParameterizedType) field.getGenericType())
                .getActualTypeArguments();

        if (actualTypeArguments.length > 0) {
            return (Class<?>) actualTypeArguments[0];
        }

        return null;
    }

    private static boolean isList(Field field) {
        return List.class.isAssignableFrom(field.getType());
    }

    private <T> T constructContainerWithParams(Class<T> cls, Object[] params)
            throws NoSuchMethodException, InstantiationException, IllegalAccessException,
            InvocationTargetException {
        T page;
        List<Object> paramsList = new ArrayList<>();
        for (int i = 0; i < params.length; i++) {
            paramsList.add(params[i]);
        }

        try {
            return ReflectionUtils.newInstance(cls, paramsList.toArray());
        } catch (Exception ex) {
            paramsList.add(0, fluentControl);
            try {
                return ReflectionUtils.newInstance(cls, paramsList.toArray());
            } catch (NoSuchMethodException ex2) {
                if (params.length != 0) {
                    throw new FluentInjectException(
                            "You provided the wrong arguments to the newInstance method, "
                                    + "if you just want to use a page with a default constructor, use @Inject or newInstance("
                                    + cls.getSimpleName() + ".class)",
                            ex);
                } else {
                    throw ex;
                }
            }
        }
    }

    private Object initFieldElements(ElementLocatorFactory factory, Object container, Field field) {
        ElementLocator locator = factory.createLocator(field);
        if (locator == null) {
            return null;
        }

        try {
            if (isListOfFluentWebElement(field)) {
                return initFieldAsListOfFluentWebElement(locator, container, field);
            } else if (isList(field)) {
                return initFieldAsList(locator, container, field);
            } else if (isComponent(field)) {
                return initFieldAsElement(locator, container, field);
            }
        } catch (IllegalAccessException e) {
            throw new FluentInjectException("Unable to find an accessible constructor with an argument of type WebElement in " + field.getType(), e);
        }
        return null;
    }

    private Object initFieldAsElement(ElementLocator locator, Object container, Field field) throws IllegalAccessException {
        final InvocationHandler handler = new LocatingElementHandler(locator);
        WebElement proxy = (WebElement) Proxy.newProxyInstance(container.getClass().getClassLoader(), new Class[]{WebElement.class, Locatable.class}, handler);
        Object proxyWrapper = wrapElement(proxy, field.getType());
        ReflectionUtils.set(field, container, proxyWrapper);
        return proxyWrapper;
    }

    private List<?> initFieldAsList(ElementLocator locator, Object container, Field field) throws IllegalAccessException {
        final InvocationHandler handler = new ArrayListInvocationHandler(locator, getFirstGenericType(field));
        List<?> proxy = (List<?>) Proxy.newProxyInstance(
                container.getClass().getClassLoader(), new Class[]{List.class}, handler);
        ReflectionUtils.set(field, container, proxy);
        return proxy;
    }

    private FluentList<? extends FluentWebElement>  initFieldAsListOfFluentWebElement(ElementLocator locator, Object container, Field field) throws IllegalAccessException {
        final InvocationHandler handler = new FluentListInvocationHandler(locator, getFirstGenericType(field));
        FluentList<? extends FluentWebElement> proxy = (FluentList<? extends FluentWebElement>) Proxy.newProxyInstance(
                container.getClass().getClassLoader(), new Class[]{FluentList.class}, handler);
        ReflectionUtils.set(field, container, proxy);
        return proxy;
    }

    private <T> T wrapElement(WebElement element, Class<T> fluentElementClass) {
        return this.componentsManager.newComponent(fluentElementClass, element);
    }

    private class FluentListInvocationHandler<T> implements InvocationHandler {

        private final ElementLocator elementLocator;

        private final Class<T> fluentElementClass;

        public FluentListInvocationHandler(ElementLocator elementLocator, Class<T> fluentElementClass) {
            this.elementLocator = elementLocator;
            this.fluentElementClass = fluentElementClass;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            List<WebElement> elements = elementLocator.findElements();
            FluentListImpl list = new FluentListImpl(FluentIterable.from(elements).transform(new Function<WebElement, T>() {

                @Override
                public T apply(WebElement input) {
                    return wrapElement(input, fluentElementClass);
                }
            }).toList());
            try {
                return ReflectionUtils.invoke(method, list, args);
            } catch (InvocationTargetException e) {
                // Unwrap the underlying exception
                throw e.getCause();
            }
        }
    }

    private class ArrayListInvocationHandler<T> implements InvocationHandler {

        private final ElementLocator elementLocator;

        private final Class<T> fluentElementClass;

        public ArrayListInvocationHandler(ElementLocator elementLocator,
                                          Class<T> fluentElementClass) {
            this.elementLocator = elementLocator;
            this.fluentElementClass = fluentElementClass;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            List<WebElement> elements = elementLocator.findElements();
            List<T> list = new ArrayList<T>(FluentIterable.from(elements).transform(new Function<WebElement, T>() {
                @Override
                public T apply(WebElement input) {
                    return wrapElement(input, fluentElementClass);
                }
            }).toList());
            try {
                return ReflectionUtils.invoke(method, list, args);
            } catch (InvocationTargetException e) {
                // Unwrap the underlying exception
                throw e.getCause();
            }
        }
    }
}
