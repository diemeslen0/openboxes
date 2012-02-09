<%@ page import="org.pih.warehouse.core.RoleType" %>
<div id="footer">
	<div style="line-height: 2em;">
		&copy; 2012 <b>OpenBoxes</b> &nbsp;&nbsp; | &nbsp;&nbsp;
		<warehouse:message code="application.environment.label"/>: <b>${grails.util.GrailsUtil.environment}</b> &nbsp;&nbsp; | &nbsp;&nbsp;
		<warehouse:message code="application.version.label"/>: &nbsp;<b><g:meta name="app.version"/></b>&nbsp;&nbsp; | &nbsp;&nbsp; 
		<warehouse:message code="application.revisionNumber.label"/>: <b><g:meta name="app.revisionNumber"/></b>&nbsp;&nbsp; | &nbsp;&nbsp;					
		<warehouse:message code="application.buildDate.label"/>: <b><g:meta name="app.buildDate"/></b>&nbsp;&nbsp; | &nbsp;&nbsp;
		<warehouse:message code="application.grailsVersion.label"/>: &nbsp; <b><g:meta name="app.grails.version"></g:meta></b>&nbsp;&nbsp; | &nbsp;&nbsp;
		<%-- <warehouse:message code="default.date.label"/>: <b>${new Date() }</b>&nbsp;&nbsp; | &nbsp;&nbsp;--%>
		<warehouse:message code="default.locale.label"/>: &nbsp;  	
		<!-- show all supported locales -->
		<g:each in="${grailsApplication.config.locale.supportedLocales}" var="l">
			<g:set var="locale" value="${new Locale(l)}"/>
			<a href="${createLink(controller: 'user', action: 'updateAuthUserLocale', params: ['locale':locale,'returnUrl':request.forwardURI])}">
				<!-- fetch the display for locale based on the current locale -->
				${locale?.getDisplayName(session?.user?.locale ?: new Locale(grailsApplication.config.locale.defaultLocale))}
			</a> &nbsp;
		</g:each>
		<g:isInRole roles="[RoleType.ROLE_ADMIN,RoleType.ROLE_USER]">
			<a href="${createLink(controller: 'user', action: 'updateAuthUserLocale', params: ['locale':'debug','returnUrl':request.forwardURI])}">
				Debug
			</a>
		</g:isInRole>
		<%-- 
		&nbsp;&nbsp; | &nbsp;&nbsp;
		<warehouse:message code="default.layout.label"/>: &nbsp; 
		<g:link controller="dashboard" action="chooseLayout" params="['layout':'custom']">custom</g:link>&nbsp;
		<g:link controller="dashboard" action="chooseLayout" params="['layout':'mega']">mobile</g:link>		
		--%>
	</div>
</div>