import sys
from abc import abstractmethod
from types import MappingProxyType
from typing import AbstractSet as Set  # noqa: Y022,Y038
from typing import AsyncGenerator as AsyncGenerator
from typing import AsyncIterable as AsyncIterable
from typing import AsyncIterator as AsyncIterator
from typing import Awaitable as Awaitable
from typing import Callable as Callable
from typing import Collection as Collection
from typing import Container as Container
from typing import Coroutine as Coroutine
from typing import Generator as Generator
from typing import Generic
from typing import Hashable as Hashable
from typing import ItemsView as ItemsView
from typing import Iterable as Iterable
from typing import Iterator as Iterator
from typing import KeysView as KeysView
from typing import Mapping as Mapping
from typing import MappingView as MappingView
from typing import MutableMapping as MutableMapping
from typing import MutableSequence as MutableSequence
from typing import MutableSet as MutableSet
from typing import Protocol
from typing import Reversible as Reversible
from typing import Sequence as Sequence
from typing import Sized as Sized
from typing import TypeVar
from typing import ValuesView as ValuesView
from typing import final, runtime_checkable

__all__ = [
    "Awaitable",
    "Coroutine",
    "AsyncIterable",
    "AsyncIterator",
    "AsyncGenerator",
    "Hashable",
    "Iterable",
    "Iterator",
    "Generator",
    "Reversible",
    "Sized",
    "Container",
    "Callable",
    "Collection",
    "Set",
    "MutableSet",
    "Mapping",
    "MutableMapping",
    "MappingView",
    "KeysView",
    "ItemsView",
    "ValuesView",
    "Sequence",
    "MutableSequence",
]
if sys.version_info < (3, 14):
    from typing import ByteString as ByteString  # noqa: Y057

    __all__ += ["ByteString"]

if sys.version_info >= (3, 12):
    __all__ += ["Buffer"]

_KT_co = TypeVar("_KT_co", covariant=True)  # Key type covariant containers.
_VT_co = TypeVar("_VT_co", covariant=True)  # Value type covariant containers.

@final
class dict_keys(KeysView[_KT_co], Generic[_KT_co, _VT_co]):  # undocumented
    def __eq__(self, value: object, /) -> bool: ...
    if sys.version_info >= (3, 13):
        def isdisjoint(self, other: Iterable[_KT_co], /) -> bool: ...
    if sys.version_info >= (3, 10):
        @property
        def mapping(self) -> MappingProxyType[_KT_co, _VT_co]: ...

@final
class dict_values(ValuesView[_VT_co], Generic[_KT_co, _VT_co]):  # undocumented
    if sys.version_info >= (3, 10):
        @property
        def mapping(self) -> MappingProxyType[_KT_co, _VT_co]: ...

@final
class dict_items(ItemsView[_KT_co, _VT_co]):  # undocumented
    def __eq__(self, value: object, /) -> bool: ...
    if sys.version_info >= (3, 13):
        def isdisjoint(self, other: Iterable[tuple[_KT_co, _VT_co]], /) -> bool: ...
    if sys.version_info >= (3, 10):
        @property
        def mapping(self) -> MappingProxyType[_KT_co, _VT_co]: ...

if sys.version_info >= (3, 12):
    @runtime_checkable
    class Buffer(Protocol):
        @abstractmethod
        def __buffer__(self, flags: int, /) -> memoryview: ...
