package mobile

import (
	"go/ast"
	"go/parser"
	"go/token"
	"path/filepath"
	"strings"
	"testing"
)

// gomobile bind n'accepte qu'un sous-ensemble restreint de types dans les
// signatures exportées. Une violation ne se manifeste qu'au moment du bind,
// qui exige le NDK Android — donc potentiellement des jours après avoir écrit
// le code fautif.
//
// Ce test rejoue la règle sur l'arbre syntaxique du paquet : il attrape la
// violation dès « go test », sur n'importe quelle machine.
var scalairesSupportes = map[string]bool{
	"bool":    true,
	"string":  true,
	"int":     true,
	"int8":    true,
	"int16":   true,
	"int32":   true,
	"int64":   true,
	"float32": true,
	"float64": true,
	"error":   true,
}

func parsePackage(t *testing.T) (*token.FileSet, []*ast.File, map[string]bool) {
	t.Helper()

	paths, err := filepath.Glob("*.go")
	if err != nil {
		t.Fatalf("recherche des sources: %v", err)
	}

	fset := token.NewFileSet()
	var parsed []*ast.File
	for _, p := range paths {
		if strings.HasSuffix(p, "_test.go") {
			continue
		}
		file, err := parser.ParseFile(fset, p, nil, 0)
		if err != nil {
			t.Fatalf("analyse de %s: %v", p, err)
		}
		parsed = append(parsed, file)
	}
	if len(parsed) == 0 {
		t.Fatal("aucune source trouvée dans le paquet mobile")
	}

	// Types déclarés dans le paquet : gomobile sait les lier par pointeur.
	declared := map[string]bool{}
	for _, file := range parsed {
		for _, decl := range file.Decls {
			gen, ok := decl.(*ast.GenDecl)
			if !ok || gen.Tok != token.TYPE {
				continue
			}
			for _, spec := range gen.Specs {
				if ts, ok := spec.(*ast.TypeSpec); ok {
					declared[ts.Name.Name] = true
				}
			}
		}
	}
	return fset, parsed, declared
}

// typeSupporte reproduit la règle de gomobile pour une expression de type.
func typeSupporte(expr ast.Expr, declared map[string]bool) bool {
	switch t := expr.(type) {
	case *ast.Ident:
		return scalairesSupportes[t.Name]

	case *ast.StarExpr:
		// Un pointeur vers un type du paquet devient un objet côté Java.
		ident, ok := t.X.(*ast.Ident)
		return ok && declared[ident.Name]

	case *ast.ArrayType:
		// Seul []byte passe ; les autres slices sont refusées.
		if t.Len != nil {
			return false
		}
		elt, ok := t.Elt.(*ast.Ident)
		return ok && (elt.Name == "byte" || elt.Name == "uint8")

	default:
		return false
	}
}

func typeNom(expr ast.Expr) string {
	switch t := expr.(type) {
	case *ast.Ident:
		return t.Name
	case *ast.StarExpr:
		return "*" + typeNom(t.X)
	case *ast.ArrayType:
		return "[]" + typeNom(t.Elt)
	case *ast.MapType:
		return "map[" + typeNom(t.Key) + "]" + typeNom(t.Value)
	case *ast.InterfaceType:
		return "interface{}"
	case *ast.SelectorExpr:
		return typeNom(t.X) + "." + t.Sel.Name
	case *ast.ChanType:
		return "chan"
	case *ast.FuncType:
		return "func"
	case *ast.Ellipsis:
		return "..." + typeNom(t.Elt)
	default:
		return "?"
	}
}

// exporte reconnaît un identifiant exporté.
func exporte(name string) bool {
	return name != "" && name[0] >= 'A' && name[0] <= 'Z'
}

func TestSignaturesCompatiblesGomobile(t *testing.T) {
	fset, files, declared := parsePackage(t)

	for _, file := range files {
		for _, decl := range file.Decls {
			fn, ok := decl.(*ast.FuncDecl)
			if !ok || !exporte(fn.Name.Name) {
				continue
			}

			// Les méthodes sur un type non exporté ne sont pas liées.
			label := fn.Name.Name
			if fn.Recv != nil && len(fn.Recv.List) > 0 {
				recv := typeNom(fn.Recv.List[0].Type)
				if !exporte(strings.TrimPrefix(recv, "*")) {
					continue
				}
				label = recv + "." + label
			}

			checkFields := func(fields *ast.FieldList, kind string) {
				if fields == nil {
					return
				}
				for _, field := range fields.List {
					if !typeSupporte(field.Type, declared) {
						t.Errorf("%s: %s de type %s, non supporté par gomobile bind (%s)",
							label, kind, typeNom(field.Type), fset.Position(field.Pos()))
					}
				}
			}

			checkFields(fn.Type.Params, "paramètre")
			checkFields(fn.Type.Results, "résultat")

			// error doit être le dernier résultat, jamais ailleurs.
			if res := fn.Type.Results; res != nil {
				for i, field := range res.List {
					if typeNom(field.Type) == "error" && i != len(res.List)-1 {
						t.Errorf("%s: error doit être le dernier résultat", label)
					}
				}
			}
		}
	}
}

// Les types exportés du paquet sont liés en entier par gomobile : leurs champs
// exportés doivent eux aussi respecter la règle. C'est la raison pour laquelle
// les structures de sérialisation JSON de ce paquet sont non exportées — elles
// contiennent des slices, que gomobile refuserait.
func TestChampsExportesCompatiblesGomobile(t *testing.T) {
	fset, files, declared := parsePackage(t)

	for _, file := range files {
		for _, decl := range file.Decls {
			gen, ok := decl.(*ast.GenDecl)
			if !ok || gen.Tok != token.TYPE {
				continue
			}
			for _, spec := range gen.Specs {
				ts, ok := spec.(*ast.TypeSpec)
				if !ok || !exporte(ts.Name.Name) {
					continue
				}
				st, ok := ts.Type.(*ast.StructType)
				if !ok || st.Fields == nil {
					continue
				}
				for _, field := range st.Fields.List {
					for _, name := range field.Names {
						if !exporte(name.Name) {
							continue
						}
						if !typeSupporte(field.Type, declared) {
							t.Errorf("%s.%s de type %s, non supporté par gomobile bind (%s)",
								ts.Name.Name, name.Name, typeNom(field.Type),
								fset.Position(field.Pos()))
						}
					}
				}
			}
		}
	}
}

// Contrôle du contrôle : la règle doit bien rejeter ce que gomobile rejette.
func TestTypeSupporteRejetteLesTypesInterdits(t *testing.T) {
	declared := map[string]bool{"App": true}

	acceptes := []string{"string", "bool", "int", "int64", "float64", "error", "*App", "[]byte"}
	refuses := []string{"uint", "uint64", "[]string", "map[string]int", "interface{}", "chan", "func", "*Inconnu"}

	parse := func(src string) ast.Expr {
		expr, err := parser.ParseExpr(src)
		if err != nil {
			t.Fatalf("expression %q: %v", src, err)
		}
		return expr
	}

	for _, src := range acceptes {
		if !typeSupporte(parse(src), declared) {
			t.Errorf("%s devrait être accepté", src)
		}
	}
	for _, src := range refuses {
		if src == "chan" {
			src = "chan int"
		}
		if src == "func" {
			src = "func()"
		}
		if typeSupporte(parse(src), declared) {
			t.Errorf("%s devrait être refusé", src)
		}
	}
}
