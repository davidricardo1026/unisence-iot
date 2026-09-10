package com.unisence.iot.common.api;

import jakarta.servlet.ServletRequest;
import org.springframework.beans.BeanUtils;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.validation.BindException;
import org.springframework.web.bind.ServletRequestParameterPropertyValues;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * {@link PageRequest} 专用参数解析器（契约见 api-standard.md §一.4）。
 * <p>
 * Spring MVC 默认数据绑定因运行期类型擦除会把 {@code query} 字段实例化为 {@code Object}，
 * 导致 Service 层强转 Query DTO 时抛 {@code ClassCastException}。
 * 本解析器从 <b>方法签名</b> 上取得真实泛型 T（方法参数泛型保留在字节码中），
 * 实例化后绑定 {@code query.} 前缀的请求参数，与前端 flattenParams 展平规则一一对应。
 */
public class PageRequestArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String QUERY_PREFIX = "query";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return PageRequest.class == parameter.getParameterType();
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) throws Exception {
        int pageNum = intParam(webRequest, "pageNum", PageRequest.DEFAULT_PAGE_NUM);
        int pageSize = intParam(webRequest, "pageSize", PageRequest.DEFAULT_PAGE_SIZE);
        return PageRequest.of(pageNum, pageSize, bindQuery(parameter, webRequest, binderFactory));
    }

    private Object bindQuery(MethodParameter parameter, NativeWebRequest webRequest,
                             WebDataBinderFactory binderFactory) throws Exception {
        Class<?> queryType = ResolvableType.forMethodParameter(parameter).getGeneric(0).resolve();
        if (queryType == null || queryType == Object.class) {
            return null;
        }
        Object query = BeanUtils.instantiateClass(queryType);
        WebDataBinder binder = binderFactory.createBinder(webRequest, query, QUERY_PREFIX);
        ServletRequest servletRequest = webRequest.getNativeRequest(ServletRequest.class);
        binder.bind(new ServletRequestParameterPropertyValues(servletRequest, QUERY_PREFIX, "."));
        binder.validate();
        if (binder.getBindingResult().hasErrors()) {
            throw new BindException(binder.getBindingResult());
        }
        return binder.getTarget();
    }

    private int intParam(NativeWebRequest webRequest, String name, int defaultValue) {
        String value = webRequest.getParameter(name);
        return (value == null || value.isBlank()) ? defaultValue : Integer.parseInt(value);
    }
}
