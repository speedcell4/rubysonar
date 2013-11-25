package org.yinwang.pysonar;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yinwang.pysonar.ast.Node;
import org.yinwang.pysonar.types.Type;
import org.yinwang.pysonar.types.UnionType;

import java.util.*;
import java.util.Map.Entry;


public class Scope
{
    public enum ScopeType
    {
        CLASS,
        INSTANCE,
        FUNCTION,
        MODULE,
        GLOBAL,
        SCOPE
    }


    @Nullable
    private Map<String, Binding> table;  // stays null for most scopes (mem opt)
    @Nullable
    public Scope parent;      // all are non-null except global table
    @Nullable
    private Scope forwarding; // link to the closest non-class scope, for lifting functions out
    @Nullable
    private List<Scope> supers;
    @Nullable
    private Set<String> globalNames;
    private ScopeType scopeType;
    private Type type;
    @NotNull
    private String path = "";


    public Scope(@Nullable Scope parent, ScopeType type)
    {
        this.parent = parent;
        this.scopeType = type;

        if (type == ScopeType.CLASS)
        {
            this.forwarding = parent == null ? null : parent.getForwarding();
        }
        else
        {
            this.forwarding = this;
        }
    }


    public Scope(@NotNull Scope s)
    {
        if (s.table != null)
        {
            this.table = new HashMap<>();
            this.table.putAll(s.table);
        }
        this.parent = s.parent;
        this.scopeType = s.scopeType;
        this.forwarding = s.forwarding;
        this.supers = s.supers;
        this.globalNames = s.globalNames;
        this.type = s.type;
        this.path = s.path;
    }


    // erase and overwrite this to s's contents
    public void overwrite(@NotNull Scope s)
    {
        this.table = s.table;
        this.parent = s.parent;
        this.scopeType = s.scopeType;
        this.forwarding = s.forwarding;
        this.supers = s.supers;
        this.globalNames = s.globalNames;
        this.type = s.type;
        this.path = s.path;
    }


    @NotNull
    public Scope copy()
    {
        return new Scope(this);
    }


    public void merge(Scope other)
    {
        for (Map.Entry<String, Binding> e1 : getInternalTable().entrySet())
        {
            Binding b1 = e1.getValue();
            Binding b2 = other.getInternalTable().get(e1.getKey());

            // both branch have the same name, need merge
            if (b2 != null && b1 != b2)
            {
                Def d1 = b1.getDef();
                Def d2 = b2.getDef();
                Type t = UnionType.union(b1.getType(), b2.getType());
                Binding b = this.insert(e1.getKey(), d1.getNode(), t, b1.getKind());
                b.addDef(d2);
            }
        }

        for (Map.Entry<String, Binding> e2 : other.getInternalTable().entrySet())
        {
            Binding b1 = getInternalTable().get(e2.getKey());
            Binding b2 = e2.getValue();

            // both branch have the same name, need merge
            if (b1 == null && b1 != b2)
            {
                this.update(b2.getName(), b2);
            }
        }
    }


    public static Scope merge(Scope scope1, Scope scope2)
    {
        Scope ret = scope1.copy();
        ret.merge(scope2);
        return ret;
    }


    public void setParent(@Nullable Scope parent)
    {
        this.parent = parent;
    }


    @Nullable
    public Scope getParent()
    {
        return parent;
    }


    public Scope getForwarding()
    {
        if (forwarding != null)
        {
            return forwarding;
        }
        else
        {
            return this;
        }
    }


    public void addSuper(Scope sup)
    {
        if (supers == null)
        {
            supers = new ArrayList<>();
        }
        supers.add(sup);
    }


    public void setScopeType(ScopeType type)
    {
        this.scopeType = type;
    }


    public ScopeType getScopeType()
    {
        return scopeType;
    }


    public void addGlobalName(@NotNull String name)
    {
        if (globalNames == null)
        {
            globalNames = new HashSet<>();
        }
        globalNames.add(name);
    }


    public boolean isGlobalName(String name)
    {
        if (globalNames != null)
        {
            return globalNames.contains(name);
        }
        else if (parent != null)
        {
            return parent.isGlobalName(name);
        }
        else
        {
            return false;
        }
    }


    public void remove(String id)
    {
        if (table != null)
        {
            table.remove(id);
        }
    }


    // create new binding and insert
    @NotNull
    public Binding insert(String id, Node node, Type type, Binding.Kind kind)
    {
        Binding b = new Binding(id, node, type, kind);
        b.setQname(extendPath(id));
        return update(id, b);
    }


    // directly insert a given binding
    @NotNull
    public Binding update(String id, @NotNull Binding b)
    {
        getInternalTable().put(id, b);
        return b;
    }


    public void setPath(@NotNull String path)
    {
        this.path = path;
    }


    @NotNull
    public String getPath()
    {
        return path;
    }


    public Type getType()
    {
        return type;
    }


    public void setType(Type type)
    {
        this.type = type;
    }


    /**
     * Look up a name in the current symbol table only. Don't recurse on the
     * parent table.
     */
    @Nullable
    public Binding lookupLocal(String name)
    {
        if (table == null)
        {
            return null;
        }
        else
        {
            return table.get(name);
        }
    }


    /**
     * Look up a name (String) in the current symbol table.  If not found,
     * recurse on the parent table.
     */
    @Nullable
    public Binding lookup(String name)
    {
        Binding b = getModuleBindingIfGlobal(name);
        if (b != null)
        {
            return b;
        }
        else
        {
            Binding ent = lookupLocal(name);
            if (ent != null)
            {
                return ent;
            }
            else if (getParent() != null)
            {
                return getParent().lookup(name);
            }
            else
            {
                return null;
            }
        }
    }


    /**
     * Look up a name in the module if it is declared as global, otherwise look
     * it up locally.
     */
    @Nullable
    public Binding lookupScope(String name)
    {
        Binding b = getModuleBindingIfGlobal(name);
        if (b != null)
        {
            return b;
        }
        else
        {
            return lookupLocal(name);
        }
    }


    /**
     * Look up an attribute in the type hierarchy.  Don't look at parent link,
     * because the enclosing scope may not be a super class. The search is
     * "depth first, left to right" as in Python's (old) multiple inheritance
     * rule. The new MRO can be implemented, but will probably not introduce
     * much difference.
     */
    @NotNull
    private static Set<Scope> looked = new HashSet<>();    // circularity prevention


    @Nullable
    public Binding lookupAttr(String attr)
    {
        if (looked.contains(this))
        {
            return null;
        }
        else
        {
            Binding b = lookupLocal(attr);
            if (b != null)
            {
                return b;
            }
            else
            {
                if (supers != null && !supers.isEmpty())
                {
                    looked.add(this);
                    for (Scope p : supers)
                    {
                        b = p.lookupAttr(attr);
                        if (b != null)
                        {
                            looked.remove(this);
                            return b;
                        }
                    }
                    looked.remove(this);
                    return null;
                }
                else
                {
                    return null;
                }
            }
        }
    }


    /**
     * Look for a binding named {@code name} and if found, return its type.
     */
    @Nullable
    public Type lookupType(String name)
    {
        Binding b = lookup(name);
        if (b == null)
        {
            return null;
        }
        else
        {
            return b.getType();
        }
    }


    /**
     * Look for a attribute named {@code attr} and if found, return its type.
     */
    @Nullable
    public Type lookupAttrType(String attr)
    {
        Binding b = lookupAttr(attr);
        if (b == null)
        {
            return null;
        }
        else
        {
            return b.getType();
        }
    }


    /**
     * Find a symbol table of a certain type in the enclosing scopes.
     */
    @Nullable
    private Scope getSymtabOfType(ScopeType type)
    {
        if (scopeType == type)
        {
            return this;
        }
        else if (parent == null)
        {
            return null;
        }
        else
        {
            return parent.getSymtabOfType(type);
        }
    }


    /**
     * Returns the global scope (i.e. the module scope for the current module).
     */
    @NotNull
    public Scope getGlobalTable()
    {
        Scope result = getSymtabOfType(ScopeType.MODULE);
        if (result != null)
        {
            return result;
        }
        else
        {
            Util.die("Couldn't find global table. Shouldn't happen");
            return this;
        }
    }


    /**
     * If {@code name} is declared as a global, return the module binding.
     */
    @Nullable
    private Binding getModuleBindingIfGlobal(String name)
    {
        if (isGlobalName(name))
        {
            Scope module = getGlobalTable();
            if (module != this)
            {
                return module.lookupLocal(name);
            }
        }
        return null;
    }


    public void putAll(@NotNull Scope other)
    {
        getInternalTable().putAll(other.getInternalTable());
    }


    @NotNull
    public Set<String> keySet()
    {
        if (table != null)
        {
            return table.keySet();
        }
        else
        {
            return Collections.emptySet();
        }
    }


    @NotNull
    public Collection<Binding> values()
    {
        if (table != null)
        {
            return table.values();
        }
        return Collections.emptySet();
    }


    @NotNull
    public Set<Entry<String, Binding>> entrySet()
    {
        if (table != null)
        {
            return table.entrySet();
        }
        return Collections.emptySet();
    }


    public boolean isEmpty()
    {
        return table == null || table.isEmpty();
    }


    @Nullable
    public String extendPath(@NotNull String name)
    {
        if (name.endsWith(".py"))
        {
            name = Util.moduleNameFor(name);
        }

        if (path.equals(""))
        {
            return name;
        }

        String sep;
        switch (scopeType)
        {
            case MODULE:
            case CLASS:
            case INSTANCE:
            case SCOPE:
                sep = ".";
                break;
            case FUNCTION:
                sep = ".";
                break;
            default:
                Util.msg("unsupported context for extendPath: " + scopeType);
                return path;
        }

        return path + sep + name;
    }


    @NotNull
    private Map<String, Binding> getInternalTable()
    {
        if (this.table == null)
        {
            this.table = new HashMap<>();
        }
        return this.table;
    }


    @NotNull
    @Override
    public String toString()
    {
        return "<Scope:" + getScopeType() + ":" +
                (table == null ? "{}" : table.keySet()) + ">";
    }

}
