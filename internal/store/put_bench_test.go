package store

import (
	"fmt"
	"strings"
	"testing"
)

// bancPut mesure l'enregistrement répété d'une note de 285 ko, comme le fait
// l'enregistrement automatique de l'éditeur, dans un cache de n notes.
//
// Le coût ne doit pas croître avec le nombre de notes quand un quota est
// posé : c'est le réglage par défaut de l'application (250 Mo).
func bancPut(b *testing.B, notes int, local bool, quota int64) {
	s, err := Open(b.TempDir())
	if err != nil {
		b.Fatal(err)
	}
	s.localOnly = local
	s.quota = quota
	petite := []byte(strings.Repeat("x", 2000))
	for i := 0; i < notes; i++ {
		if err := s.Put(fmt.Sprintf("n%04d.md", i), petite); err != nil {
			b.Fatal(err)
		}
	}
	grosse := []byte(strings.Repeat("abcdefghij ", 26000))
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		grosse[i%len(grosse)] ^= 1
		if err := s.Put("grosse.md", grosse); err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkPutLocal10Notes(b *testing.B)   { bancPut(b, 10, true, UnlimitedQuota) }
func BenchmarkPutLocal1000Notes(b *testing.B) { bancPut(b, 1000, true, UnlimitedQuota) }
func BenchmarkPutQuota10Notes(b *testing.B)   { bancPut(b, 10, false, 250<<20) }
func BenchmarkPutQuota1000Notes(b *testing.B) { bancPut(b, 1000, false, 250<<20) }
