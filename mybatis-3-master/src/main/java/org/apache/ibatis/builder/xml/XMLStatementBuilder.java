/**
 *    Copyright 2009-2020 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.builder.xml;

import java.util.List;
import java.util.Locale;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 */
public class XMLStatementBuilder extends BaseBuilder {

  private final MapperBuilderAssistant builderAssistant;
  private final XNode context;
  private final String requiredDatabaseId;

  public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context) {
    this(configuration, builderAssistant, context, null);
  }

  public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context, String databaseId) {
    super(configuration);
    this.builderAssistant = builderAssistant;
    this.context = context;
    this.requiredDatabaseId = databaseId;
  }

  public void parseStatementNode() {
    // ID 属性
    String id = context.getStringAttribute("id");
    // 获取当前 crud 的 databaseId
    String databaseId = context.getStringAttribute("databaseId");

    // 验证 crud 的 databaseId 与 全局的 databaseId 是否相等
    // 或者已经存在了
    if (!databaseIdMatchesCurrent(id, databaseId, this.requiredDatabaseId)) {
      return;
    }

    // node 的名称 select | update | insert | delete
    String nodeName = context.getNode().getNodeName();
    SqlCommandType sqlCommandType = SqlCommandType.valueOf(nodeName.toUpperCase(Locale.ENGLISH));
    // 是否为查询语句
    boolean isSelect = sqlCommandType == SqlCommandType.SELECT;
    // 是否要刷新缓存 flushCache 将其设置为 true，任何时候只要语句被调用，都会导致本地缓存和二级缓存都会被清空，默认值：false。
    boolean flushCache = context.getBooleanAttribute("flushCache", !isSelect);
    // 是否有 使用指定缓存 将其设置为 true，将会导致本条语句的结果被二级缓存，默认值：对 select 元素为 true。
    boolean useCache = context.getBooleanAttribute("useCache", isSelect);
    // 这个设置仅针对嵌套结果 select 语句适用：如果为 true，就是假设包含了嵌套结果集或是分组了，这样的话当返回一个主结果行的时候，
    // 就不会发生有对前面结果集的引用的情况。这就使得在获取嵌套的结果集的时候不至于导致内存不够用。默认值：false。
    boolean resultOrdered = context.getBooleanAttribute("resultOrdered", false);

    // Include Fragments before parsing
    // 这个解析要在 sql 标签之后  解析 include 标签
    // 将 include 节点替换成 sql 节点中定义的sql 片段，边将其中的 ${xxx} 占位符替换为真实的参数，该解析过程在 applyIncludes 方法中实现
    XMLIncludeTransformer includeParser = new XMLIncludeTransformer(configuration, builderAssistant);
    // 解析 include 完毕
    includeParser.applyIncludes(context.getNode());

    // 参数类型
    String parameterType = context.getStringAttribute("parameterType");
    Class<?> parameterTypeClass = resolveClass(parameterType);

    // lang 属性，默认返回 XMLLanguageDriver 实现动态 SQL 的功能
    String lang = context.getStringAttribute("lang");
    LanguageDriver langDriver = getLanguageDriver(lang);

    // Parse selectKey after includes and remove them.
    // 解析 selectKey 标签，注意哦，解析 selectKey 的时候，已经删除了 该Node
    processSelectKeyNodes(id, parameterTypeClass, langDriver);

    // Parse the SQL (pre: <selectKey> and <include> were parsed and removed)
    KeyGenerator keyGenerator;
    String keyStatementId = id + SelectKeyGenerator.SELECT_KEY_SUFFIX;
    keyStatementId = builderAssistant.applyCurrentNamespace(keyStatementId, true);
    // 可能没有指定 selectKey 这标签
    if (configuration.hasKeyGenerator(keyStatementId)) {
      keyGenerator = configuration.getKeyGenerator(keyStatementId);
    } else {

      // 1、指定了 selectKey 标签中的，优先使用
      // 2、指定了 insert 标签中的 useGeneratedKeys=true | (全局配置了 isUseGeneratedKeys，并且为select 语句)，则为 Jdbc3KeyGenerator.INSTANCE
      // 3、否则为 NoKeyGenerator.INSTANCE，即不没有使用

      keyGenerator = context.getBooleanAttribute("useGeneratedKeys",
          configuration.isUseGeneratedKeys() && SqlCommandType.INSERT.equals(sqlCommandType))
          ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
    }

    /**
     * 到了这里，咱们的 select | update | insert | delete
     * 已经解析了 include 中的 sql 、selectKey 中的信息
     * 并且已经将他们删除相应的 node，只保留 include selectKey 解析完后的xml 节点的属性信息(NODE-text类型)
     *
     * 只剩下 <if> <where> <trim> #{} ${}
     * 和自己本身写的 sql 了
     */

    // node 节点中的 sql 被解析成为 SqlSource ，这个sqlSource 可能是带动态参数或者占位符的
    SqlSource sqlSource = langDriver.createSqlSource(configuration, context, parameterTypeClass);
    StatementType statementType = StatementType.valueOf(context.getStringAttribute("statementType", StatementType.PREPARED.toString()));
    Integer fetchSize = context.getIntAttribute("fetchSize");
    Integer timeout = context.getIntAttribute("timeout");
    String parameterMap = context.getStringAttribute("parameterMap");
    String resultType = context.getStringAttribute("resultType");
    Class<?> resultTypeClass = resolveClass(resultType);
    String resultMap = context.getStringAttribute("resultMap");
    String resultSetType = context.getStringAttribute("resultSetType");
    ResultSetType resultSetTypeEnum = resolveResultSetType(resultSetType);
    if (resultSetTypeEnum == null) {
      resultSetTypeEnum = configuration.getDefaultResultSetType();
    }
    String keyProperty = context.getStringAttribute("keyProperty");
    String keyColumn = context.getStringAttribute("keyColumn");
    String resultSets = context.getStringAttribute("resultSets");

    builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
        fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
        resultSetTypeEnum, flushCache, useCache, resultOrdered,
        keyGenerator, keyProperty, keyColumn, databaseId, langDriver, resultSets);
  }

  private void processSelectKeyNodes(String id, Class<?> parameterTypeClass, LanguageDriver langDriver) {
    // 获取 selectKey 集合
    List<XNode> selectKeyNodes = context.evalNodes("selectKey");
    if (configuration.getDatabaseId() != null) {
      parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, configuration.getDatabaseId());
    }
    // 解析
    parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, null);
    // 解析完毕，删除
    removeSelectKeyNodes(selectKeyNodes);
  }

  private void parseSelectKeyNodes(String parentId, List<XNode> list, Class<?> parameterTypeClass, LanguageDriver langDriver, String skRequiredDatabaseId) {
    for (XNode nodeToHandle : list) {
      String id = parentId + SelectKeyGenerator.SELECT_KEY_SUFFIX;
      String databaseId = nodeToHandle.getStringAttribute("databaseId");
      if (databaseIdMatchesCurrent(id, databaseId, skRequiredDatabaseId)) {
        parseSelectKeyNode(id, nodeToHandle, parameterTypeClass, langDriver, databaseId);
      }
    }
  }

  /** 这里才是解析 */
  private void parseSelectKeyNode(String id, XNode nodeToHandle, Class<?> parameterTypeClass, LanguageDriver langDriver, String databaseId) {
    String resultType = nodeToHandle.getStringAttribute("resultType");
    Class<?> resultTypeClass = resolveClass(resultType);
    // statementType 语句类型枚举值, 预编译 | 普通 | 存储过程，默认为预编译的
    StatementType statementType = StatementType.valueOf(nodeToHandle.getStringAttribute("statementType", StatementType.PREPARED.toString()));
    String keyProperty = nodeToHandle.getStringAttribute("keyProperty");
    String keyColumn = nodeToHandle.getStringAttribute("keyColumn");
    boolean executeBefore = "BEFORE".equals(nodeToHandle.getStringAttribute("order", "AFTER"));

    // defaults
    boolean useCache = false;
    boolean resultOrdered = false;
    KeyGenerator keyGenerator = NoKeyGenerator.INSTANCE;
    Integer fetchSize = null;
    Integer timeout = null;
    boolean flushCache = false;
    String parameterMap = null;
    String resultMap = null;
    ResultSetType resultSetTypeEnum = null;

    // 创建 SqlSource
    SqlSource sqlSource = langDriver.createSqlSource(configuration, nodeToHandle, parameterTypeClass);
    // 这里类型指定为 查询
    SqlCommandType sqlCommandType = SqlCommandType.SELECT;

    builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
        fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
        resultSetTypeEnum, flushCache, useCache, resultOrdered,
        keyGenerator, keyProperty, keyColumn, databaseId, langDriver, null);

    id = builderAssistant.applyCurrentNamespace(id, false);

    MappedStatement keyStatement = configuration.getMappedStatement(id, false);
    // 保存的Map 是一个定制化的  StrictMap
    // SelectKeyGenerator 中有 MappedStatement 、executeBefore 执行之前还是只有的sql
    configuration.addKeyGenerator(id, new SelectKeyGenerator(keyStatement, executeBefore));
  }

  private void removeSelectKeyNodes(List<XNode> selectKeyNodes) {
    for (XNode nodeToHandle : selectKeyNodes) {
      nodeToHandle.getParent().getNode().removeChild(nodeToHandle.getNode());
    }
  }

  private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
    if (requiredDatabaseId != null) {
      return requiredDatabaseId.equals(databaseId);
    }
    if (databaseId != null) {
      return false;
    }
    id = builderAssistant.applyCurrentNamespace(id, false);
    if (!this.configuration.hasStatement(id, false)) {
      return true;
    }
    // skip this statement if there is a previous one with a not null databaseId
    MappedStatement previous = this.configuration.getMappedStatement(id, false); // issue #2
    return previous.getDatabaseId() == null;
  }

  private LanguageDriver getLanguageDriver(String lang) {
    Class<? extends LanguageDriver> langClass = null;
    if (lang != null) {
      langClass = resolveClass(lang);
    }
    return configuration.getLanguageDriver(langClass);
  }

}
