package org.intermine.objectstore.intermine;

/*
 * Copyright (C) 2002-2004 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.WeakHashMap;

import org.intermine.codegen.InterMineTorqueModelOutput;
import org.intermine.metadata.ClassDescriptor;
import org.intermine.metadata.CollectionDescriptor;
import org.intermine.metadata.FieldDescriptor;
import org.intermine.metadata.Model;
import org.intermine.metadata.ReferenceDescriptor;
import org.intermine.model.InterMineObject;
import org.intermine.objectstore.ObjectStoreException;
import org.intermine.objectstore.query.Query;
import org.intermine.objectstore.query.QueryCast;
import org.intermine.objectstore.query.QueryClass;
import org.intermine.objectstore.query.QueryNode;
import org.intermine.objectstore.query.QueryEvaluable;
import org.intermine.objectstore.query.QueryField;
import org.intermine.objectstore.query.QueryExpression;
import org.intermine.objectstore.query.QueryFunction;
import org.intermine.objectstore.query.QueryValue;
import org.intermine.objectstore.query.FromElement;
import org.intermine.objectstore.query.Constraint;
import org.intermine.objectstore.query.ConstraintOp;
import org.intermine.objectstore.query.ConstraintSet;
import org.intermine.objectstore.query.SimpleConstraint;
import org.intermine.objectstore.query.SubqueryConstraint;
import org.intermine.objectstore.query.ClassConstraint;
import org.intermine.objectstore.query.ContainsConstraint;
import org.intermine.objectstore.query.BagConstraint;
import org.intermine.objectstore.query.QueryReference;
import org.intermine.objectstore.query.QueryObjectReference;
import org.intermine.objectstore.query.QueryCollectionReference;
import org.intermine.objectstore.query.UnknownTypeValue;
import org.intermine.sql.Database;
import org.intermine.util.AlwaysMap;
import org.intermine.util.DatabaseUtil;
import org.intermine.util.DynamicUtil;

import org.apache.log4j.Logger;
import org.apache.torque.engine.database.model.Domain;
import org.apache.torque.engine.database.model.SchemaType;
import org.apache.torque.engine.platform.Platform;
import org.apache.torque.engine.platform.PlatformFactory;

/**
 * Code to generate an sql statement from a Query object.
 *
 * @author Matthew Wakeling
 * @author Andrew Varley
 * @author Richard Smith
 */
public class SqlGenerator
{
    private static final Logger LOG = Logger.getLogger(SqlGenerator.class);
    protected static final int QUERY_NORMAL = 0;
    protected static final int QUERY_SUBQUERY_FROM = 1;
    protected static final int QUERY_SUBQUERY_CONSTRAINT = 2;
    protected static final int ID_ONLY = 2;
    protected static final int NO_ALIASES_ALL_FIELDS = 3;

    protected static Map sqlCache = Collections.synchronizedMap(new WeakHashMap());

    /**
     * Generates a query to retrieve a single object from the database, by id.
     *
     * @param id the id of the object to fetch
     * @param clazz a Class of the object - if unsure use InterMineObject
     * @param model the model
     * @return a String suitable for passing to an SQL server
     * @throws ObjectStoreException if the given class is not in the model
     */
    public static String generateQueryForId(Integer id, Class clazz,
            Model model) throws ObjectStoreException {
        ClassDescriptor cld = model.getClassDescriptorByName(clazz.getName());
        if (cld == null) {
            throw new ObjectStoreException(clazz.toString() + " is not in the model");
        }
        return "SELECT DISTINCT a1_.OBJECT AS a1_ FROM " + DatabaseUtil.getTableName(cld)
            + " AS a1_ WHERE a1_.id = " + id.toString() + " LIMIT 2";
    }

    /**
     * Registers an offset for a given query. This is used later on to speed up queries that use
     * big offsets.
     *
     * @param q the Query
     * @param start the offset
     * @param model the Model to look up metadata in
     * @param db the Database that the ObjectStore uses
     * @param value a value, such that adding a WHERE component first_order_field &gt; value with
     * OFFSET 0 is equivalent to the original query with OFFSET offset
     */
    public static void registerOffset(Query q, int start, Model model, Database db,
            Object value) {
        try {
            synchronized (q) {
                TreeMap cached = (TreeMap) sqlCache.get(q);
                if (cached != null) {
                    SortedMap headMap = cached.headMap(new Integer(start + 1));
                    Integer lastKey = null;
                    try {
                        lastKey = (Integer) headMap.lastKey();
                    } catch (NoSuchElementException e) {
                    }
                    if (lastKey != null) {
                        int offset = lastKey.intValue();
                        if (start - offset < 100000) {
                            return;
                        }
                    }
                }
                QueryNode firstOrderBy = null;
                try {
                    firstOrderBy = (QueryNode) q.getOrderBy().iterator().next();
                } catch (NoSuchElementException e) {
                    firstOrderBy = (QueryNode) q.getSelect().iterator().next();
                }
                if (firstOrderBy instanceof QueryClass) {
                    firstOrderBy = new QueryField((QueryClass) firstOrderBy, "id");
                }
                String sql = generate(q, model, db, new SimpleConstraint((QueryEvaluable)
                            firstOrderBy, ConstraintOp.GREATER_THAN, new QueryValue(value)),
                        QUERY_NORMAL);
                if (cached == null) {
                    cached = new TreeMap();
                    sqlCache.put(q, cached);
                }
                cached.put(new Integer(start), sql);
                LOG.info("Created cache entry for offset " + start + " (cache contains "
                        + cached.keySet() + ") for query " + q + ", sql = " + sql);
            }
        } catch (ObjectStoreException e) {
            LOG.error("Error while registering offset for query " + q + ": " + e);
        }
    }

    /**
     * Converts a Query object into an SQL String. To produce an SQL query that does not have
     * OFFSET and LIMIT clauses, set start to 0, and limit to Integer.MAX_VALUE.
     *
     * @param q the Query to convert
     * @param start the number of the first row for the query to return, numbered from zero
     * @param limit the maximum number of rows for the query to return
     * @param model the Model to look up metadata in
     * @param db the Database that the ObjectStore uses
     * @return a String suitable for passing to an SQL server
     * @throws ObjectStoreException if something goes wrong
     */
    public static String generate(Query q, int start, int limit, Model model, Database db)
            throws ObjectStoreException {
        synchronized (q) {
            TreeMap cached = (TreeMap) sqlCache.get(q);
            if (cached != null) {
                SortedMap headMap = cached.headMap(new Integer(start + 1));
                Integer lastKey = null;
                try {
                    lastKey = (Integer) headMap.lastKey();
                } catch (NoSuchElementException e) {
                }
                if (lastKey != null) {
                    int offset = lastKey.intValue();
                    return cached.get(lastKey)
                        + ((limit == Integer.MAX_VALUE ? "" : " LIMIT " + limit)
                            + (start == offset ? "" : " OFFSET " + (start - offset)));
                }
            }
            String sql = generate(q, model, db, null, QUERY_NORMAL);
            if (cached == null) {
                cached = new TreeMap();
                sqlCache.put(q, cached);
            }
            cached.put(new Integer(0), sql);
            return sql + ((limit == Integer.MAX_VALUE ? "" : " LIMIT " + limit)
                        + (start == 0 ? "" : " OFFSET " + start));
        }
    }

    /**
     * Converts a Query object into an SQL String.
     *
     * @param q the Query to convert
     * @param model the Model to look up metadata in
     * @param db the Database that the ObjectStore uses
     * @param offsetCon an additional constraint for improving the speed of large offsets
     * @param kind Query type
     * @return a String suitable for passing to an SQL server
     * @throws ObjectStoreException if something goes wrong
     */
    protected static String generate(Query q, Model model, Database db, SimpleConstraint offsetCon,
            int kind) throws ObjectStoreException {
        State state = new State();
        state.setDb(db);
        buildFromComponent(state, q, model);
        buildWhereClause(state, q, q.getConstraint(), model);
        buildWhereClause(state, q, offsetCon, model);
        String orderBy = (kind == QUERY_NORMAL ? buildOrderBy(state, q, model) : "");
        StringBuffer retval = new StringBuffer("SELECT ")
            .append(q.isDistinct() ? "DISTINCT " : "")
            .append(buildSelectComponent(state, q, model, kind))
            .append(state.getFrom())
            .append(state.getWhere())
            .append(buildGroupBy(q, model, state))
            .append(orderBy);
        return retval.toString();
    }

    /**
     * Builds the FROM list for the SQL query.
     *
     * @param state the current Sql Query state
     * @param q the Query
     * @param model the Model
     * @throws ObjectStoreException if something goes wrong
     */
    protected static void buildFromComponent(State state, Query q, Model model)
            throws ObjectStoreException {
        Set fromElements = q.getFrom();
        Iterator fromIter = fromElements.iterator();
        while (fromIter.hasNext()) {
            FromElement fromElement = (FromElement) fromIter.next();
            if (fromElement instanceof QueryClass) {
                QueryClass qc = (QueryClass) fromElement;
                Set classes = DynamicUtil.decomposeClass(qc.getType());
                Set clds = new HashSet();
                Map aliases = new HashMap();
                int sequence = 0;
                String lastAlias = "";
                Iterator classIter = classes.iterator();
                while (classIter.hasNext()) {
                    Class cls = (Class) classIter.next();
                    ClassDescriptor cld = model.getClassDescriptorByName(cls.getName());
                    if (cld == null) {
                        throw new ObjectStoreException(cls.toString() + " is not in the model");
                    }
                    clds.add(cld);
                    String baseAlias = (String) q.getAliases().get(qc);
                    if (sequence == 0) {
                        aliases.put(cld, baseAlias);
                        state.addToFrom(DatabaseUtil.getTableName(cld) + " AS " + baseAlias);
                    } else {
                        aliases.put(cld, baseAlias + "_" + sequence);
                        state.addToFrom(DatabaseUtil.getTableName(cld) + " AS " + baseAlias
                                + "_" + sequence);
                        if (state.getWhereBuffer().length() > 0) {
                            state.addToWhere(" AND ");
                        }
                        state.addToWhere(baseAlias + lastAlias + ".id = " + baseAlias
                                + "_" + sequence + ".id");
                        lastAlias = "_" + sequence;
                    }
                    sequence++;
                }
                Map fields = model.getFieldDescriptorsForClass(qc.getType());
                Map fieldToAlias = state.getFieldToAlias(qc);
                Iterator fieldIter = fields.entrySet().iterator();
                while (fieldIter.hasNext()) {
                    Map.Entry fieldEntry = (Map.Entry) fieldIter.next();
                    FieldDescriptor field = (FieldDescriptor) fieldEntry.getValue();
                    String name = field.getName();
                    Iterator aliasIter = aliases.entrySet().iterator();
                    while (aliasIter.hasNext()) {
                        Map.Entry aliasEntry = (Map.Entry) aliasIter.next();
                        ClassDescriptor cld = (ClassDescriptor) aliasEntry.getKey();
                        String alias = (String) aliasEntry.getValue();
                        if (cld.getAllFieldDescriptors().contains(field)) {
                            fieldToAlias.put(name, alias);
                        }
                    }
                }
            } else if (fromElement instanceof Query) {
                state.addToFrom("(" + generate((Query) fromElement, model,
                                state.getDb(), null, QUERY_SUBQUERY_FROM) + ") AS "
                        + ((String) q.getAliases().get(fromElement)));
                state.setFieldToAlias(fromElement, new AlwaysMap(q.getAliases().get(fromElement)));
            }
        }
    }

    /**
     * Builds the WHERE clause for the SQL query.
     *
     * @param state the current Sql Query state
     * @param q the Query
     * @param c the Constraint
     * @param model the Model
     * @throws ObjectStoreException if something goes wrong
     */
    protected static void buildWhereClause(State state, Query q, Constraint c, Model model)
            throws ObjectStoreException {
        if (c != null) {
            if (state.getWhereBuffer().length() > 0) {
                state.addToWhere(" AND ");
            }
            constraintToString(state, c, q, model);
        }
    }

    /**
     * Converts a Constraint object into a String suitable for putting in an SQL query.
     *
     * @param state the object to place text into
     * @param c the Constraint object
     * @param q the Query
     * @param model the Model
     * @throws ObjectStoreException if something goes wrong
     */
    protected static void constraintToString(State state, Constraint c, Query q, Model model)
            throws ObjectStoreException {
        if (c instanceof ConstraintSet) {
            constraintSetToString(state, (ConstraintSet) c, q, model);
        } else if (c instanceof SimpleConstraint) {
            simpleConstraintToString(state, (SimpleConstraint) c, q, model);
        } else if (c instanceof SubqueryConstraint) {
            subqueryConstraintToString(state, (SubqueryConstraint) c, q, model);
        } else if (c instanceof ClassConstraint) {
            classConstraintToString(state, (ClassConstraint) c, q, model);
        } else if (c instanceof ContainsConstraint) {
            containsConstraintToString(state, (ContainsConstraint) c, q, model);
        } else if (c instanceof BagConstraint) {
            bagConstraintToString(state, (BagConstraint) c, q, model);
        } else {
            throw (new IllegalArgumentException("Unknown constraint type: " + c));
        }
    }

    /**
     * Converts a ConstraintSet object into a String suitable for putting in an SQL query.
     *
     * @param state the object to place text into
     * @param c the ConstraintSet object
     * @param q the Query
     * @param model the Model
     * @throws ObjectStoreException if something goes wrong
     */
    protected static void constraintSetToString(State state, ConstraintSet c, Query q, Model model)
            throws ObjectStoreException {
        ConstraintOp op = c.getOp();
        boolean negate = (op == ConstraintOp.NAND) || (op == ConstraintOp.NOR);
        boolean disjunctive = (op == ConstraintOp.OR) || (op == ConstraintOp.NOR);
        if (c.getConstraints().isEmpty()) {
            state.addToWhere((disjunctive ? negate : !negate) ? "true" : "false");
        } else {
            state.addToWhere(negate ? "( NOT (" : "(");
            boolean needComma = false;
            Set constraints = c.getConstraints();
            Iterator constraintIter = constraints.iterator();
            while (constraintIter.hasNext()) {
                Constraint subC = (Constraint) constraintIter.next();
                if (needComma) {
                    state.addToWhere(disjunctive ? " OR " : " AND ");
                }
                needComma = true;
                constraintToString(state, subC, q, model);
            }
            state.addToWhere(negate ? "))" : ")");
        }
    }

    /**
     * Converts a SimpleConstraint object into a String suitable for putting in an SQL query.
     *
     * @param state the object to place text into
     * @param c the SimpleConstraint object
     * @param q the Query
     * @param model the Model
     * @throws ObjectStoreException if something goes wrong
     */
    protected static void simpleConstraintToString(State state, SimpleConstraint c, Query q,
            Model model) throws ObjectStoreException {
        queryEvaluableToString(state.getWhereBuffer(), c.getArg1(), q, state);
        state.addToWhere(" " + c.getOp().toString());
        if (c.getArg2() != null) {
            state.addToWhere(" ");
            queryEvaluableToString(state.getWhereBuffer(), c.getArg2(), q, state);
        }
    }

    /**
     * Converts a SubqueryConstraint object into a String suitable for putting in an SQL query.
     *
     * @param state the object to place text into
     * @param c the SubqueryConstraint object
     * @param q the Query
     * @param model the Model
     * @throws ObjectStoreException if something goes wrong
     */
    protected static void subqueryConstraintToString(State state, SubqueryConstraint c, Query q,
            Model model) throws ObjectStoreException {
        Query subQ = c.getQuery();
        QueryEvaluable qe = c.getQueryEvaluable();
        QueryClass cls = c.getQueryClass();
        if (qe != null) {
            queryEvaluableToString(state.getWhereBuffer(), qe, q, state);
        } else {
            queryClassToString(state.getWhereBuffer(), cls, q, model, QUERY_SUBQUERY_CONSTRAINT,
                    state);
        }
        state.addToWhere(" " + c.getOp().toString() + " (" + generate(subQ, model,
                        state.getDb(), null, QUERY_SUBQUERY_CONSTRAINT) + ")");
    }

    /**
     * Converts a ClassConstraint object into a String suitable for putting in an SQL query.
     *
     * @param state the object to place text into
     * @param c the ClassConstraint object
     * @param q the Query
     * @param model the Model
     * @throws ObjectStoreException if something goes wrong
     */
    protected static void classConstraintToString(State state, ClassConstraint c, Query q,
            Model model) throws ObjectStoreException {
        QueryClass arg1 = c.getArg1();
        QueryClass arg2QC = c.getArg2QueryClass();
        InterMineObject arg2O = c.getArg2Object();
        queryClassToString(state.getWhereBuffer(), arg1, q, model, ID_ONLY, state);
        state.addToWhere(" " + c.getOp().toString() + " ");
        if (arg2QC != null) {
            queryClassToString(state.getWhereBuffer(), arg2QC, q, model, ID_ONLY, state);
        } else if (arg2O.getId() != null) {
            objectToString(state.getWhereBuffer(), arg2O);
        } else {
            throw new ObjectStoreException("ClassConstraint cannot contain a InterMineObject"
                    + " without an ID set");
        }
    }

    /**
     * Converts a ContainsConstraint object into a String suitable for putting in an SQL query.
     *
     * @param state the object to place text into
     * @param c the ContainsConstraint object
     * @param q the Query
     * @param model the Model
     * @throws ObjectStoreException if something goes wrong
     */
    protected static void containsConstraintToString(State state, ContainsConstraint c,
            Query q, Model model) throws ObjectStoreException {
        QueryReference arg1 = c.getReference();
        QueryClass arg2 = c.getQueryClass();
        InterMineObject arg2Obj = c.getObject();
        Map fieldNameToFieldDescriptor = model.getFieldDescriptorsForClass(arg1.getQueryClass()
                .getType());
        ReferenceDescriptor arg1Desc = (ReferenceDescriptor)
            fieldNameToFieldDescriptor.get(arg1.getFieldName());
        if (arg1Desc == null) {
            throw new ObjectStoreException(arg1.getQueryClass().getType().toString()
                    + " is not in the model");
        }
        if (arg1 instanceof QueryObjectReference) {
            String arg1Alias = (String) state.getFieldToAlias(arg1.getQueryClass()).get(arg1Desc
                    .getName());
            if (c.getOp().equals(ConstraintOp.IS_NULL) || c.getOp().equals(ConstraintOp
                        .IS_NOT_NULL)) {
                state.addToWhere(arg1Alias + "." + DatabaseUtil.getColumnName(arg1Desc)
                        + " " + c.getOp().toString());
            } else {
                state.addToWhere(arg1Alias + "." + DatabaseUtil.getColumnName(arg1Desc)
                        + (c.getOp() == ConstraintOp.CONTAINS ? " = " : " != "));
                if (arg2 == null) {
                    objectToString(state.getWhereBuffer(), arg2Obj);
                } else {
                    queryClassToString(state.getWhereBuffer(), arg2, q, model, ID_ONLY, state);
                }
            }
        } else if (arg1 instanceof QueryCollectionReference) {
            if (arg1Desc.relationType() == FieldDescriptor.ONE_N_RELATION) {
                String arg2Alias = (String) state.getFieldToAlias(arg2)
                    .get(arg1Desc.getReverseReferenceDescriptor().getName());
                queryClassToString(state.getWhereBuffer(), arg1.getQueryClass(), q, model, ID_ONLY,
                        state);
                state.addToWhere((c.getOp() == ConstraintOp.CONTAINS ? " = " : " != ") + arg2Alias
                        + "."
                        + DatabaseUtil.getColumnName(arg1Desc.getReverseReferenceDescriptor()));
            } else {
                CollectionDescriptor arg1ColDesc = (CollectionDescriptor) arg1Desc;
                String indirectTableAlias = state.getIndirectAlias();
                state.addToFrom(DatabaseUtil.getIndirectionTableName(arg1ColDesc) + " AS "
                        + indirectTableAlias);
                state.addToWhere(c.getOp().equals(ConstraintOp.CONTAINS) ? "(" : "( NOT (");
                queryClassToString(state.getWhereBuffer(), arg1.getQueryClass(), q, model, ID_ONLY,
                        state);
                state.addToWhere(" = " + indirectTableAlias + "."
                        + DatabaseUtil.getInwardIndirectionColumnName(arg1ColDesc) + " AND "
                        + indirectTableAlias + "."
                        + DatabaseUtil.getOutwardIndirectionColumnName(arg1ColDesc) + " = ");
                queryClassToString(state.getWhereBuffer(), arg2, q, model, ID_ONLY, state);
                state.addToWhere(c.getOp().equals(ConstraintOp.CONTAINS) ? ")" : "))");
            }
        }
    }

    /**
     * Converts a BagConstraint object into a String suitable for putting on an SQL query.
     *
     * @param state the object to place text into
     * @param c the BagConstraint object
     * @param q the Query
     * @param model the Model
     * @throws ObjectStoreException if something goes wrong
     */
    protected static void bagConstraintToString(State state, BagConstraint c, Query q,
            Model model) throws ObjectStoreException {
        Class type = c.getQueryNode().getType();
        String leftHandSide;
        if (c.getQueryNode() instanceof QueryEvaluable) {
            StringBuffer lhsBuffer = new StringBuffer();
            queryEvaluableToString(lhsBuffer, (QueryEvaluable) c.getQueryNode(), q, state);
            leftHandSide = lhsBuffer.toString() + " = ";
        } else {
            StringBuffer lhsBuffer = new StringBuffer();
            queryClassToString(lhsBuffer, (QueryClass) c.getQueryNode(), q, model, ID_ONLY, state);
            leftHandSide = lhsBuffer.toString() + " = ";
        }
        SortedSet filteredBag = new TreeSet();
        Iterator bagIter = c.getBag().iterator();
        while (bagIter.hasNext()) {
            Object bagItem = bagIter.next();
            if (type.isInstance(bagItem)) {
                StringBuffer constraint = new StringBuffer(leftHandSide);
                objectToString(constraint, bagItem);
                filteredBag.add(constraint.toString());
            }
        }
        if (filteredBag.isEmpty()) {
            state.addToWhere(c.getOp() == ConstraintOp.IN ? "false" : "true");
        } else {
            boolean needComma = false;
            Iterator orIter = filteredBag.iterator();
            while (orIter.hasNext()) {
                state.addToWhere(needComma ? " OR " : (c.getOp() == ConstraintOp.IN ? "("
                            : "( NOT ("));
                needComma = true;
                state.addToWhere((String) orIter.next());
            }
            state.addToWhere(c.getOp() == ConstraintOp.IN ? ")" : "))");
        }
    }

    /**
     * Converts an Object to a String, in a form suitable for SQL.
     *
     * @param buffer a StringBuffer to add text to
     * @param value the Object to convert
     * @throws ObjectStoreException if something goes wrong
     */
    public static void objectToString(StringBuffer buffer, Object value)
            throws ObjectStoreException {
        if (value instanceof UnknownTypeValue) {
            buffer.append(value.toString());
        } else if (value instanceof InterMineObject) {
            Integer id = ((InterMineObject) value).getId();
            if (id == null) {
                throw new ObjectStoreException("InterMineObject found"
                        + " without an ID set");
            }
            buffer.append(id.toString());
        } else if (value instanceof Date) {
            buffer.append(DatabaseUtil.objectToString(new Long(((Date) value).getTime())));
        } else {
            buffer.append(DatabaseUtil.objectToString(value));
        }
    }

    /**
     * Converts a QueryClass to a String.
     *
     * @param buffer the StringBuffer to add text to
     * @param qc the QueryClass to convert
     * @param q the Query
     * @param model the Model
     * @param kind the type of the output requested
     * @param state a State object
     */
    protected static void queryClassToString(StringBuffer buffer, QueryClass qc, Query q,
            Model model, int kind, State state) {
        String alias = (String) q.getAliases().get(qc);
        if (kind == QUERY_SUBQUERY_CONSTRAINT) {
            buffer.append(alias)
                .append(".id");
        } else {
            buffer.append(alias)
                .append(".OBJECT");
            if ((kind == QUERY_NORMAL) || (kind == QUERY_SUBQUERY_FROM)) {
                buffer.append(" AS ")
                    .append(alias.equals(alias.toLowerCase()) ? alias : "\"" + alias + "\"");
            }
            if ((kind == QUERY_SUBQUERY_FROM) || (kind == NO_ALIASES_ALL_FIELDS)) {
                Set fields = model.getClassDescriptorByName(qc.getType().getName())
                    .getAllFieldDescriptors();
                Map fieldMap = new TreeMap();
                Iterator fieldIter = fields.iterator();
                while (fieldIter.hasNext()) {
                    FieldDescriptor field = (FieldDescriptor) fieldIter.next();
                    String columnName = DatabaseUtil.getColumnName(field);
                    if (columnName != null) {
                        fieldMap.put(columnName, field);
                    }
                }
                Iterator fieldMapIter = fieldMap.entrySet().iterator();
                while (fieldMapIter.hasNext()) {
                    Map.Entry fieldEntry = (Map.Entry) fieldMapIter.next();
                    FieldDescriptor field = (FieldDescriptor) fieldEntry.getValue();
                    String columnName = DatabaseUtil.getColumnName(field);

                    buffer.append(", ")
                        .append((String) state.getFieldToAlias(qc).get(field.getName()))
                        .append(".")
                        .append(columnName);
                    if (kind == QUERY_SUBQUERY_FROM) {
                        buffer.append(" AS ")
                            .append(alias.equals(alias.toLowerCase()) ? alias + columnName
                                    : "\"" + alias + columnName + "\"");
                    }
                }
            } else {
                buffer.append(", ")
                    .append(alias)
                    .append(".id AS ")
                    .append(alias.equals(alias.toLowerCase()) ? alias + "id" : "\"" + alias
                            + "id" + "\"");
            }
        }
    }

    /**
     * Converts a QueryEvaluable into a String suitable for an SQL query String.
     *
     * @param buffer the StringBuffer to add text to
     * @param node the QueryEvaluable
     * @param q the Query
     * @param state a State object
     * @throws ObjectStoreException if something goes wrong
     */
    protected static void queryEvaluableToString(StringBuffer buffer, QueryEvaluable node,
            Query q, State state) throws ObjectStoreException {
        if (node instanceof QueryField) {
            QueryField nodeF = (QueryField) node;
            FromElement nodeClass = nodeF.getFromElement();
            String classAlias = (String) state.getFieldToAlias(nodeClass).get(nodeF.getFieldName());

            buffer.append(classAlias)
                .append(".")
                .append(DatabaseUtil.generateSqlCompatibleName(nodeF.getFieldName()))
                .append(nodeF.getSecondFieldName() == null
                        ? "" : DatabaseUtil.generateSqlCompatibleName(nodeF.getSecondFieldName()));
        } else if (node instanceof QueryExpression) {
            QueryExpression nodeE = (QueryExpression) node;
            if (nodeE.getOperation() == QueryExpression.SUBSTRING) {
                QueryEvaluable arg1 = nodeE.getArg1();
                QueryEvaluable arg2 = nodeE.getArg2();
                QueryEvaluable arg3 = nodeE.getArg3();

                buffer.append("SUBSTR(");
                queryEvaluableToString(buffer, arg1, q, state);
                buffer.append(", ");
                queryEvaluableToString(buffer, arg2, q, state);
                if (arg3 != null) {
                    buffer.append(", ");
                    queryEvaluableToString(buffer, arg3, q, state);
                }
                buffer.append(")");
            } else if (nodeE.getOperation() == QueryExpression.INDEX_OF) {
                QueryEvaluable arg1 = nodeE.getArg1();
                QueryEvaluable arg2 = nodeE.getArg2();

                buffer.append("STRPOS(");
                queryEvaluableToString(buffer, arg1, q, state);
                buffer.append(", ");
                queryEvaluableToString(buffer, arg2, q, state);
                buffer.append(")");
            } else {
                QueryEvaluable arg1 = nodeE.getArg1();
                QueryEvaluable arg2 = nodeE.getArg2();
                String op = null;
                switch (nodeE.getOperation()) {
                    case QueryExpression.ADD:
                        op = " + ";
                        break;
                    case QueryExpression.SUBTRACT:
                        op = " - ";
                        break;
                    case QueryExpression.MULTIPLY:
                        op = " * ";
                        break;
                    case QueryExpression.DIVIDE:
                        op = " / ";
                        break;
                    default:
                        throw (new IllegalArgumentException("Invalid QueryExpression operation: "
                                                            + nodeE.getOperation()));
                }
                buffer.append("(");
                queryEvaluableToString(buffer, arg1, q, state);
                buffer.append(op);
                queryEvaluableToString(buffer, arg2, q, state);
                buffer.append(")");
            }
        } else if (node instanceof QueryFunction) {
            QueryFunction nodeF = (QueryFunction) node;
            switch (nodeF.getOperation()) {
            case QueryFunction.COUNT:
                buffer.append("COUNT(*)");
                break;
            case QueryFunction.SUM:
                buffer.append("SUM(");
                queryEvaluableToString(buffer, nodeF.getParam(), q, state);
                buffer.append(")");
                break;
            case QueryFunction.AVERAGE:
                buffer.append("AVG(");
                queryEvaluableToString(buffer, nodeF.getParam(), q, state);
                buffer.append(")");
                break;
            case QueryFunction.MIN:
                buffer.append("MIN(");
                queryEvaluableToString(buffer, nodeF.getParam(), q, state);
                buffer.append(")");
                break;
            case QueryFunction.MAX:
                buffer.append("MAX(");
                queryEvaluableToString(buffer, nodeF.getParam(), q, state);
                buffer.append(")");
                break;
            default:
                throw (new IllegalArgumentException("Invalid QueryFunction operation: "
                                                    + nodeF.getOperation()));
            }
        } else if (node instanceof QueryValue) {
            QueryValue nodeV = (QueryValue) node;
            Object value = nodeV.getValue();
            objectToString(buffer, value);
        } else if (node instanceof QueryCast) {
            buffer.append("(");
            queryEvaluableToString(buffer, ((QueryCast) node).getValue(), q, state);
            buffer.append(")::");
            String torqueTypeName = InterMineTorqueModelOutput.generateJdbcType(node.getType()
                    .getName());
            SchemaType torqueType = SchemaType.getEnum(torqueTypeName);
            Platform torquePlatform = PlatformFactory.getPlatformFor(state.getDb().getPlatform()
                    .toLowerCase());
            Domain torqueDomain = torquePlatform.getDomainForSchemaType(torqueType);
            buffer.append(torqueDomain.getSqlType());
        } else {
            throw (new IllegalArgumentException("Invalid QueryEvaluable: " + node));
        }
    }

    /**
     * Builds a String representing the SELECT component of the Sql query.
     *
     * @param state the current Sql Query state
     * @param q the Query
     * @param model the Model
     * @param kind the kind of output requested
     * @return a String
     * @throws ObjectStoreException if something goes wrong
     */
    protected static String buildSelectComponent(State state, Query q, Model model, int kind)
            throws ObjectStoreException {
        boolean needComma = false;
        StringBuffer retval = new StringBuffer();
        Iterator iter = q.getSelect().iterator();
        while (iter.hasNext()) {
            QueryNode node = (QueryNode) iter.next();
            if (needComma) {
                retval.append(", ");
            }
            needComma = true;
            if (node instanceof QueryClass) {
                queryClassToString(retval, (QueryClass) node, q, model, kind, state);
            } else if (node instanceof QueryEvaluable) {
                queryEvaluableToString(retval, (QueryEvaluable) node, q, state);
                String alias = (String) q.getAliases().get(node);
                if (kind == QUERY_NORMAL) {
                    retval.append(" AS " + (alias.equals(alias.toLowerCase()) ? alias : "\"" + alias
                                + "\""));
                } else if (kind == QUERY_SUBQUERY_FROM) {
                    retval.append(" AS " + alias);
                }
            }
        }
        iter = state.getOrderBy().iterator();
        while (iter.hasNext()) {
            String orderByField = (String) iter.next();
            if (needComma) {
                retval.append(", ");
            }
            needComma = true;
            retval.append(orderByField);
        }
        return retval.toString();
    }

    /**
     * Builds a String representing the GROUP BY component of the Sql query.
     *
     * @param q the Query
     * @param model the Model
     * @param state a State object
     * @return a String
     * @throws ObjectStoreException if something goes wrong
     */
    protected static String buildGroupBy(Query q, Model model,
            State state) throws ObjectStoreException {
        StringBuffer retval = new StringBuffer();
        boolean needComma = false;
        Iterator groupByIter = q.getGroupBy().iterator();
        while (groupByIter.hasNext()) {
            QueryNode node = (QueryNode) groupByIter.next();
            retval.append(needComma ? ", " : " GROUP BY ");
            needComma = true;
            if (node instanceof QueryClass) {
                queryClassToString(retval, (QueryClass) node, q, model, NO_ALIASES_ALL_FIELDS,
                        state);
            } else {
                queryEvaluableToString(retval, (QueryEvaluable) node, q, state);
            }
        }
        return retval.toString();
    }

    /**
     * Builds a String representing the ORDER BY component of the Sql query.
     *
     * @param state the current Sql Query state
     * @param q the Query
     * @param model the Model
     * @return a String
     * @throws ObjectStoreException if something goes wrong
     */
    protected static String buildOrderBy(State state, Query q, Model model)
            throws ObjectStoreException {
        StringBuffer retval = new StringBuffer();
        boolean needComma = false;
        List orderBy = new ArrayList(q.getOrderBy());
        orderBy.addAll(q.getSelect());
        Iterator orderByIter = orderBy.iterator();
        while (orderByIter.hasNext()) {
            QueryNode node = (QueryNode) orderByIter.next();
            if (!(node instanceof QueryValue)) {
                retval.append(needComma ? ", " : " ORDER BY ");
                needComma = true;
                if (node instanceof QueryClass) {
                    queryClassToString(retval, (QueryClass) node, q, model, ID_ONLY, state);
                } else {
                    queryEvaluableToString(retval, (QueryEvaluable) node, q, state);
                    if (!q.getSelect().contains(node)) {
                        StringBuffer buffer = new StringBuffer();
                        queryEvaluableToString(buffer, (QueryEvaluable) node, q, state);
                        buffer.append(" AS ")
                            .append(state.getOrderByAlias());
                        state.addToOrderBy(buffer.toString());
                    }
                }
            }
        }
        return retval.toString();
    }

    private static class State
    {
        private StringBuffer whereText = new StringBuffer();
        private StringBuffer fromText = new StringBuffer();
        private Set orderBy = new LinkedHashSet();
        private int number = 0;
        private Map fromToFieldToAlias = new HashMap();
        private Database db;

        public State() {
        }

        public String getWhere() {
            return (whereText.length() == 0 ? "" : " WHERE " + whereText.toString());
        }

        public StringBuffer getWhereBuffer() {
            return whereText;
        }

        public String getFrom() {
            return fromText.toString();
        }

        public void addToWhere(String text) {
            whereText.append(text);
        }

        public void addToFrom(String text) {
            if (fromText.length() == 0) {
                fromText.append(" FROM ").append(text);
            } else {
                fromText.append(", ").append(text);
            }
        }

        public String getIndirectAlias() {
            return "indirect" + (number++);
        }

        public String getOrderByAlias() {
            return "orderbyfield" + (number++);
        }

        public void addToOrderBy(String s) {
            orderBy.add(s);
        }

        public Set getOrderBy() {
            return orderBy;
        }

        public Map getFieldToAlias(FromElement from) {
            Map retval = (Map) fromToFieldToAlias.get(from);
            if (retval == null) {
                retval = new HashMap();
                fromToFieldToAlias.put(from, retval);
            }
            return retval;
        }

        public void setFieldToAlias(FromElement from, Map map) {
            fromToFieldToAlias.put(from, map);
        }

        public void setDb(Database db) {
            this.db = db;
        }

        public Database getDb() {
            return db;
        }
    }
}
