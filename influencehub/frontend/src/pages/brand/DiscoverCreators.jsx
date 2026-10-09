import { useState, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { SearchX, CheckCircle, MessageSquare, Send } from 'lucide-react'
import AppLayout from '../../components/layout/AppLayout'
import Card from '../../components/ui/Card'
import Button from '../../components/ui/Button'
import Avatar from '../../components/ui/Avatar'
import TagPill from '../../components/ui/TagPill'
import Textarea from '../../components/ui/Textarea'
import Modal from '../../components/ui/Modal'
import EmptyState from '../../components/ui/EmptyState'
import Pagination from '../../components/ui/Pagination'
import { Skeleton } from '../../components/ui/Skeleton'
import { useToast } from '../../store/toastStore'
import { useAuthStore } from '../../store/authStore'
import client from '../../api/client'

const PAGE_SIZE = 12

export default function DiscoverCreators() {
  const [results, setResults] = useState([])
  const [page, setPage] = useState(1)
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  // Map of creatorUserId → request status ('pending'|'accepted'|'rejected'|null)
  const [statusMap, setStatusMap] = useState({})
  const [requestModal, setRequestModal] = useState(null) // creator object
  const [description, setDescription] = useState('')
  const [sending, setSending] = useState(false)
  const navigate = useNavigate()
  const { toast } = useToast()
  const user = useAuthStore((s) => s.user)

  const hours = new Date().getHours()
  const greeting = hours < 12 ? 'morning' : hours < 17 ? 'afternoon' : 'evening'

  const loadCreators = useCallback(() => {
    setLoading(true)
    // Server-side pagination: the backend returns PAGE_SIZE creators per page plus the total count
    client.get('/api/creators', { params: { page, size: PAGE_SIZE } })
      .then(res => {
        const creators = res.data.creators || []
        setResults(creators)
        setTotal(res.data.total || 0)
        // Build statusMap from the per-creator requestStatus returned by backend
        const map = {}
        creators.forEach(c => {
          if (c.requestStatus) map[c.userId] = c.requestStatus
        })
        setStatusMap(map)
      })
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [page])

  useEffect(() => { loadCreators() }, [loadCreators])

  const openRequestModal = (creator) => {
    setRequestModal(creator)
    setDescription('')
  }

  const sendRequest = async () => {
    if (!requestModal) return
    setSending(true)
    try {
      await client.post('/api/requests', { creatorId: requestModal.userId, description })
      // Update button immediately to 'pending'
      setStatusMap(prev => ({ ...prev, [requestModal.userId]: 'pending' }))
      toast.success('Collaboration request sent!')
      setRequestModal(null)
    } catch (err) {
      const msg = err?.response?.data
      toast.error(typeof msg === 'string' ? msg : 'Failed to send request')
    } finally {
      setSending(false)
    }
  }

  const startChat = async (creator) => {
    try {
      const res = await client.post('/api/conversations', { otherUserId: creator.userId })
      navigate(`/messages?conversationId=${res.data.id}`)
    } catch { toast.error('Failed to start conversation') }
  }

  // Returns the action button(s) for each creator card based on request status
  const ActionButtons = ({ creator }) => {
    const status = statusMap[creator.userId]

    if (status === 'pending') {
      return (
        <Button size="sm" fullWidth variant="ghost-dark" disabled icon={CheckCircle}>
          Requested
        </Button>
      )
    }
    if (status === 'accepted') {
      return (
        <Button size="sm" fullWidth onClick={() => startChat(creator)} icon={MessageSquare}>
          Message
        </Button>
      )
    }
    // null or 'rejected' — show View + Request
    return (
      <>
        <Button variant="ghost-dark" size="sm" fullWidth onClick={() => navigate(`/brand/creator/${creator.id}`)}>
          View
        </Button>
        <Button size="sm" fullWidth onClick={() => openRequestModal(creator)}>
          {status === 'rejected' ? 'Request Again' : 'Request'}
        </Button>
      </>
    )
  }

  return (
    <AppLayout role="brand">
      {/* Welcome Greeting Banner */}
      <div className="bg-white border border-[#E0E0DB] border-l-4 border-l-[#108A00] rounded-[10px] p-[18px_20px] mb-[24px]">
        <h2 className="text-[18px] font-semibold text-[#1C1C1C]">
          Good {greeting}, {user?.companyName || user?.name || 'there'} 👋
        </h2>
        <p className="text-[14px] text-[#888888] mt-[4px]">
          Discover creators and send collaboration requests directly!
        </p>
      </div>

      {/* Creators Grid */}
      <div>
        <div className="flex justify-between items-center mb-[16px]">
          <h3 className="text-[16px] font-semibold text-[#1C1C1C]">
            All Creators {!loading && `(${total})`}
          </h3>
        </div>

        {loading ? (
          <div className="grid grid-cols-3 gap-[14px]">
            {Array.from({ length: 9 }).map((_, i) => (
              <Skeleton key={i} height={220} />
            ))}
          </div>
        ) : !results.length ? (
          <Card>
            <EmptyState
              icon={SearchX}
              title="No creators found"
              description="Creators will appear here once they register on the platform."
            />
          </Card>
        ) : (
          <div className="grid grid-cols-3 gap-[14px]">
            {results.map(c => (
              <Card key={c.id} className="!p-0 overflow-hidden">
                <div className="h-[80px] bg-[#F0F0EB] relative">
                  <Avatar name={c.name} size={40} className="absolute -bottom-[20px] left-[14px] border-2 border-white" />
                </div>
                <div className="p-[28px_14px_14px]">
                  <p className="text-[14px] font-semibold text-[#1C1C1C] truncate">{c.name}</p>
                  <p className="text-[12px] text-[#888888]">@{c.handle}</p>
                  {c.niche && <TagPill label={c.niche} className="mt-[4px]" />}
                  {c.followers && (
                    <p className="text-[12px] text-[#888888] mt-[4px]">{c.followers} followers</p>
                  )}
                  <div className="flex gap-[6px] mt-[10px]">
                    <ActionButtons creator={c} />
                  </div>
                </div>
              </Card>
            ))}
          </div>
        )}
        {total > PAGE_SIZE && (
          <Pagination currentPage={page} totalPages={Math.ceil(total / PAGE_SIZE)}
            onPageChange={(p) => { setPage(p); window.scrollTo(0, 0) }} />
        )}
      </div>

      {/* Request Modal */}
      <Modal
        isOpen={!!requestModal}
        onClose={() => setRequestModal(null)}
        title="Send Collaboration Request"
        size="sm"
        footer={
          <div className="flex w-full gap-[12px]">
            <Button variant="ghost-dark" className="flex-1" onClick={() => setRequestModal(null)}>
              Cancel
            </Button>
            <Button className="flex-1" loading={sending} onClick={sendRequest} icon={Send}>
              Send Request
            </Button>
          </div>
        }
      >
        <div className="space-y-[16px]">
          <div className="flex items-center gap-[12px] p-[12px] bg-[#F5F5F0] rounded-[8px]">
            <Avatar name={requestModal?.name} size={40} />
            <div>
              <p className="text-[14px] font-semibold text-[#1C1C1C]">{requestModal?.name}</p>
              <p className="text-[12px] text-[#888888]">@{requestModal?.handle}</p>
            </div>
          </div>
          <Textarea
            label="Description (Optional)"
            name="description"
            rows={4}
            placeholder="Briefly describe what you're looking for and why you'd like to work with this creator..."
            value={description}
            onChange={e => setDescription(e.target.value)}
          />
          <p className="text-[12px] text-[#888888] italic">
            The creator will be notified. You can discuss details once they accept.
          </p>
        </div>
      </Modal>
    </AppLayout>
  )
}
