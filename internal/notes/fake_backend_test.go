package notes

import (
	"context"
	"fmt"
	"path"
	"sort"
	"strings"
	"time"

	"opennote/internal/opencloud"
)

// fakeBackend est une implémentation en mémoire de Backend.
//
// Elle reproduit les comportements du serveur réel dont le modèle dépend :
// les erreurs sentinelles, le refus d'un If-Match périmé, et un List qui
// renvoie le contenu direct d'un dossier sans le dossier lui-même.
type fakeBackend struct {
	dirs  map[string]bool
	files map[string]*fakeFile
	seq   int
}

type fakeFile struct {
	content []byte
	etag    string
	mod     time.Time
}

func newFakeBackend() *fakeBackend {
	return &fakeBackend{
		dirs:  map[string]bool{"": true},
		files: map[string]*fakeFile{},
	}
}

func (f *fakeBackend) nextETag() string {
	f.seq++
	return fmt.Sprintf("%q", fmt.Sprintf("etag-%d", f.seq))
}

// seed installe une arborescence de départ, en créant les dossiers implicites.
func (f *fakeBackend) seed(paths ...string) {
	for _, p := range paths {
		p = CleanPath(p)
		if strings.HasSuffix(p, "/") || !strings.Contains(path.Base(p), ".") {
			f.mkdirAll(p)
			continue
		}
		f.mkdirAll(path.Dir(p))
		f.files[p] = &fakeFile{
			content: []byte("# " + path.Base(p) + "\n"),
			etag:    f.nextETag(),
			mod:     time.Date(2026, 8, 28, 9, 0, 0, 0, time.UTC),
		}
	}
}

func (f *fakeBackend) mkdirAll(p string) {
	p = CleanPath(p)
	if p == "" || p == "." {
		return
	}
	current := ""
	for _, segment := range strings.Split(p, "/") {
		current = path.Join(current, segment)
		f.dirs[current] = true
	}
}

func (f *fakeBackend) List(_ context.Context, dir string) ([]opencloud.Resource, error) {
	dir = CleanPath(dir)
	if !f.dirs[dir] {
		return nil, fmt.Errorf("fake: %s: %w", dir, opencloud.ErrNotFound)
	}

	prefix := ""
	if dir != "" {
		prefix = dir + "/"
	}

	var out []opencloud.Resource
	for d := range f.dirs {
		if d == "" || !strings.HasPrefix(d, prefix) || d == dir {
			continue
		}
		if strings.Contains(strings.TrimPrefix(d, prefix), "/") {
			continue
		}
		out = append(out, opencloud.Resource{
			Path: d, Name: path.Base(d), IsDir: true, ETag: `"dir"`,
		})
	}
	for p, file := range f.files {
		if !strings.HasPrefix(p, prefix) {
			continue
		}
		if strings.Contains(strings.TrimPrefix(p, prefix), "/") {
			continue
		}
		out = append(out, opencloud.Resource{
			Path:        p,
			Name:        path.Base(p),
			Size:        int64(len(file.content)),
			ContentType: "text/markdown",
			ETag:        file.etag,
			ModTime:     file.mod,
			FileID:      "id!" + p,
		})
	}

	sort.Slice(out, func(i, j int) bool { return out[i].Path < out[j].Path })
	return out, nil
}

func (f *fakeBackend) Stat(_ context.Context, p string) (opencloud.Resource, error) {
	p = CleanPath(p)
	if f.dirs[p] {
		return opencloud.Resource{Path: p, Name: path.Base(p), IsDir: true}, nil
	}
	file, ok := f.files[p]
	if !ok {
		return opencloud.Resource{}, fmt.Errorf("fake: %s: %w", p, opencloud.ErrNotFound)
	}
	return opencloud.Resource{
		Path: p, Name: path.Base(p),
		Size: int64(len(file.content)), ETag: file.etag, ModTime: file.mod,
	}, nil
}

func (f *fakeBackend) Read(_ context.Context, p string) ([]byte, string, error) {
	file, ok := f.files[CleanPath(p)]
	if !ok {
		return nil, "", fmt.Errorf("fake: %s: %w", p, opencloud.ErrNotFound)
	}
	return append([]byte(nil), file.content...), file.etag, nil
}

func (f *fakeBackend) Write(_ context.Context, p string, content []byte, ifMatch string) (string, error) {
	p = CleanPath(p)
	if !f.dirs[path.Dir(CleanPath("/"+p))] && path.Dir(p) != "." {
		if !f.dirs[path.Dir(p)] {
			return "", fmt.Errorf("fake: dossier parent absent pour %s: %w", p, opencloud.ErrNotFound)
		}
	}

	if ifMatch != "" {
		existing, ok := f.files[p]
		if !ok || existing.etag != ifMatch {
			return "", fmt.Errorf("fake: %s: %w", p, opencloud.ErrConflict)
		}
	}

	etag := f.nextETag()
	f.files[p] = &fakeFile{content: append([]byte(nil), content...), etag: etag, mod: time.Now()}
	return etag, nil
}

func (f *fakeBackend) WriteNew(ctx context.Context, p string, content []byte) (string, error) {
	if _, exists := f.files[CleanPath(p)]; exists {
		return "", fmt.Errorf("fake: %s: %w", p, opencloud.ErrConflict)
	}
	return f.Write(ctx, p, content, "")
}

func (f *fakeBackend) MkdirAll(_ context.Context, p string) error {
	f.mkdirAll(p)
	return nil
}

func (f *fakeBackend) Move(_ context.Context, from, to string) error {
	from, to = CleanPath(from), CleanPath(to)
	if file, ok := f.files[from]; ok {
		delete(f.files, from)
		f.files[to] = file
		return nil
	}
	if !f.dirs[from] {
		return fmt.Errorf("fake: %s: %w", from, opencloud.ErrNotFound)
	}

	for d := range f.dirs {
		if d == from || strings.HasPrefix(d, from+"/") {
			delete(f.dirs, d)
			f.dirs[to+strings.TrimPrefix(d, from)] = true
		}
	}
	for p, file := range f.files {
		if strings.HasPrefix(p, from+"/") {
			delete(f.files, p)
			f.files[to+strings.TrimPrefix(p, from)] = file
		}
	}
	return nil
}

func (f *fakeBackend) Remove(_ context.Context, p string) error {
	p = CleanPath(p)
	if _, ok := f.files[p]; ok {
		delete(f.files, p)
		return nil
	}
	if !f.dirs[p] {
		return fmt.Errorf("fake: %s: %w", p, opencloud.ErrNotFound)
	}
	for d := range f.dirs {
		if d == p || strings.HasPrefix(d, p+"/") {
			delete(f.dirs, d)
		}
	}
	for file := range f.files {
		if strings.HasPrefix(file, p+"/") {
			delete(f.files, file)
		}
	}
	return nil
}
